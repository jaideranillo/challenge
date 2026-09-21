package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.adapter.out.persistence.cursor.DeliveryPageCursor;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for {@link DeliveryQueryJdbcRepository#findPage}.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryFindPageTest {

    @Autowired
    DeliveryQueryJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void fixtures() {
        clientId = "client-page-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    // -----------------------------------------------------------------------
    // Tests 4-5: event_created_at column behaviour
    // -----------------------------------------------------------------------

    /**
     * Test 4: replay shape — event_created_at 30 days ago, created_at today.
     *
     * <p>Filter on event window returns the delivery; filter on today's window does not.
     * Without this test the denormalization is unverified.
     */
    @Test
    void findPage_replayShape_filteredByEventCreatedAt_notByCreatedAt() {
        Instant eventTs = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID id = insertDeliveryWithEca(clientId, subscriptionId, eventTs);

        // Query window around event date — should find it
        DeliveryPage inWindow = repo.findPage(clientId,
                new DeliveryPageQuery(
                        Optional.of(eventTs.minus(1, ChronoUnit.DAYS)),
                        Optional.of(eventTs.plus(1, ChronoUnit.DAYS)),
                        Optional.empty(), Optional.empty()),
                10);
        assertThat(inWindow.deliveries().stream().map(Delivery::deliveryId)).contains(id);

        // Query window around today (created_at) — should NOT find it
        Instant now = Instant.now();
        DeliveryPage todayWindow = repo.findPage(clientId,
                new DeliveryPageQuery(
                        Optional.of(now.minus(1, ChronoUnit.HOURS)),
                        Optional.of(now.plus(1, ChronoUnit.HOURS)),
                        Optional.empty(), Optional.empty()),
                10);
        assertThat(todayWindow.deliveries().stream().map(Delivery::deliveryId)).doesNotContain(id);
    }

    /**
     * Test 5: order is by event_created_at, not created_at.
     *
     * <p>Three rows where event_created_at ordering disagrees with insertion order.
     */
    @Test
    void findPage_orderedByEventCreatedAt_notCreatedAt() {
        Instant t1 = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant t2 = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant t3 = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        UUID id1 = insertDeliveryWithEca(clientId, subscriptionId, t1);
        UUID id2 = insertDeliveryWithEca(clientId, subscriptionId, t2);
        UUID id3 = insertDeliveryWithEca(clientId, subscriptionId, t3);

        DeliveryPage page = repo.findPage(clientId, DeliveryPageQuery.unfiltered(), 10);

        List<UUID> ids = page.deliveries().stream().map(Delivery::deliveryId).collect(Collectors.toList());
        // Newest first (DESC)
        int pos1 = ids.indexOf(id1);
        int pos2 = ids.indexOf(id2);
        int pos3 = ids.indexOf(id3);
        assertThat(pos3).isLessThan(pos2);
        assertThat(pos2).isLessThan(pos1);
    }

    // -----------------------------------------------------------------------
    // Tests 6-9: paging
    // -----------------------------------------------------------------------

    /**
     * Test 6: full traversal — 25 rows, limit=10, three pages, every id seen exactly once.
     */
    @Test
    void findPage_fullTraversal_allIdsSeenExactlyOnce() {
        Set<UUID> seeded = new HashSet<>();
        Instant base = Instant.now().minus(25, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 25; i++) {
            seeded.add(insertDeliveryWithEca(clientId, subscriptionId,
                    base.plus(i, ChronoUnit.MINUTES)));
        }

        Set<UUID> seen = new HashSet<>();
        Optional<String> cursor = Optional.empty();
        int pages = 0;
        boolean hasMore = true;
        while (hasMore) {
            DeliveryPage page = repo.findPage(clientId,
                    new DeliveryPageQuery(Optional.empty(), Optional.empty(), Optional.empty(), cursor),
                    10);
            for (Delivery d : page.deliveries()) {
                assertThat(seen.add(d.deliveryId())).as("no duplicates").isTrue();
            }
            hasMore = page.nextCursor().isPresent();
            cursor = page.nextCursor();
            pages++;
            if (pages > 5) break; // safety
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(pages).isEqualTo(3); // 10, 10, 5
    }

    /**
     * Test 7: fan-out boundary — 10 rows with identical event_created_at.
     *
     * <p>Without the delivery_id tiebreak, rows are lost or repeated at page boundaries.
     */
    @Test
    void findPage_fanOutBoundary_allRowsReturnedExactlyOnce() {
        Instant fanOutTs = Instant.now().minus(12, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seeded.add(insertDeliveryWithEca(clientId, subscriptionId, fanOutTs));
        }

        Set<UUID> seen = new HashSet<>();
        Optional<String> cursor = Optional.empty();
        int pages = 0;
        boolean hasMore = true;
        while (hasMore) {
            DeliveryPage page = repo.findPage(clientId,
                    new DeliveryPageQuery(Optional.empty(), Optional.empty(), Optional.empty(), cursor),
                    3);
            for (Delivery d : page.deliveries()) {
                assertThat(seen.add(d.deliveryId())).as("no duplicates in fan-out").isTrue();
            }
            hasMore = page.nextCursor().isPresent();
            cursor = page.nextCursor();
            pages++;
            if (pages > 6) break;
        }
        // 4 pages: 3, 3, 3, 1
        assertThat(seen).containsExactlyInAnyOrderElementsOf(seeded);
    }

    /** Test 8: limit exactly equal to row count returns hasMore=false. */
    @Test
    void findPage_limitEqualsRowCount_hasMoreFalse() {
        for (int i = 0; i < 5; i++) {
            insertDeliveryWithEca(clientId, subscriptionId,
                    Instant.now().minus(i, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        }
        DeliveryPage page = repo.findPage(clientId, DeliveryPageQuery.unfiltered(), 5);
        assertThat(page.deliveries()).hasSize(5);
        assertThat(page.nextCursor()).isEmpty();
    }

    /** Test 9: empty result returns empty list and no cursor, never null. */
    @Test
    void findPage_emptyResult_emptyListAndNoCursor() {
        DeliveryPage page = repo.findPage("no-such-client-" + UUID.randomUUID(),
                DeliveryPageQuery.unfiltered(), 10);
        assertThat(page.deliveries()).isNotNull().isEmpty();
        assertThat(page.nextCursor()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Tests 10-12: filters
    // -----------------------------------------------------------------------

    /** Test 10: eventCreatedFrom, eventCreatedTo, both, neither — boundary checks. */
    @Test
    void findPage_dateFilters_boundaryInclusive() {
        Instant ts = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID id = insertDeliveryWithEca(clientId, subscriptionId, ts);

        // from only (inclusive): ts itself is included (>= from)
        DeliveryPage fromOnly = repo.findPage(clientId,
                new DeliveryPageQuery(Optional.of(ts), Optional.empty(), Optional.empty(), Optional.empty()),
                10);
        assertThat(fromOnly.deliveries().stream().map(Delivery::deliveryId)).contains(id);

        // from after ts: excluded
        DeliveryPage fromAfter = repo.findPage(clientId,
                new DeliveryPageQuery(Optional.of(ts.plus(1, ChronoUnit.SECONDS)), Optional.empty(),
                        Optional.empty(), Optional.empty()),
                10);
        assertThat(fromAfter.deliveries().stream().map(Delivery::deliveryId)).doesNotContain(id);

        // to only (exclusive): ts+1s is included; ts itself excluded when to=ts
        DeliveryPage toExclusive = repo.findPage(clientId,
                new DeliveryPageQuery(Optional.empty(), Optional.of(ts), Optional.empty(), Optional.empty()),
                10);
        assertThat(toExclusive.deliveries().stream().map(Delivery::deliveryId)).doesNotContain(id);

        DeliveryPage toAfter = repo.findPage(clientId,
                new DeliveryPageQuery(Optional.empty(), Optional.of(ts.plus(1, ChronoUnit.SECONDS)),
                        Optional.empty(), Optional.empty()),
                10);
        assertThat(toAfter.deliveries().stream().map(Delivery::deliveryId)).contains(id);
    }

    /** Test 11: status filter returns only that status; combined with date window, both apply. */
    @Test
    void findPage_statusFilter_andCombinedWithDateWindow() {
        Instant ts = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        UUID pending = insertDeliveryWithEcaAndStatus(clientId, subscriptionId, ts, "PENDING");
        UUID delivered = insertDeliveryWithEcaAndStatus(clientId, subscriptionId, ts, "DELIVERED");

        // Status=PENDING only
        DeliveryPage pendingPage = repo.findPage(clientId,
                new DeliveryPageQuery(Optional.empty(), Optional.empty(),
                        Optional.of(DeliveryStatus.PENDING), Optional.empty()),
                10);
        assertThat(pendingPage.deliveries().stream().map(Delivery::deliveryId)).contains(pending);
        assertThat(pendingPage.deliveries().stream().map(Delivery::deliveryId)).doesNotContain(delivered);

        // Status=PENDING + date window
        DeliveryPage combined = repo.findPage(clientId,
                new DeliveryPageQuery(
                        Optional.of(ts.minus(1, ChronoUnit.MINUTES)),
                        Optional.of(ts.plus(1, ChronoUnit.MINUTES)),
                        Optional.of(DeliveryStatus.PENDING), Optional.empty()),
                10);
        assertThat(combined.deliveries().stream().map(Delivery::deliveryId)).contains(pending);
        assertThat(combined.deliveries().stream().map(Delivery::deliveryId)).doesNotContain(delivered);
    }

    /**
     * Test 12: every filter combination still scopes to the tenant.
     *
     * <p>A foreign-tenant row seeded throughout must never appear in any filter combination (A01).
     */
    @Test
    void findPage_allFilterCombinations_tenantIsolation_a01() {
        // Foreign-tenant row with the same event timestamp
        String foreignClientId = "client-foreign-" + UUID.randomUUID();
        UUID foreignSub = insertSubscription(foreignClientId);
        Instant ts = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        UUID foreignId = insertDeliveryWithEca(foreignClientId, foreignSub, ts);

        // Our own row
        UUID ownId = insertDeliveryWithEca(clientId, subscriptionId, ts);

        // All filter combinations
        List<DeliveryPage> pages = List.of(
                repo.findPage(clientId, DeliveryPageQuery.unfiltered(), 50),
                repo.findPage(clientId,
                        new DeliveryPageQuery(Optional.of(ts.minus(1, ChronoUnit.HOURS)), Optional.empty(),
                                Optional.empty(), Optional.empty()),
                        50),
                repo.findPage(clientId,
                        new DeliveryPageQuery(Optional.empty(), Optional.of(ts.plus(1, ChronoUnit.HOURS)),
                                Optional.empty(), Optional.empty()),
                        50),
                repo.findPage(clientId,
                        new DeliveryPageQuery(Optional.empty(), Optional.empty(),
                                Optional.of(DeliveryStatus.PENDING), Optional.empty()),
                        50)
        );

        for (DeliveryPage page : pages) {
            assertThat(page.deliveries().stream().map(Delivery::deliveryId))
                    .as("foreign row must not appear").doesNotContain(foreignId);
            assertThat(page.deliveries().stream().map(Delivery::deliveryId))
                    .as("own row must appear").contains(ownId);
        }
    }

    // -----------------------------------------------------------------------
    // Tests 13-14: cursor security and error behaviour
    // -----------------------------------------------------------------------

    /**
     * Test 13: cursor from tenant A, replayed by tenant B, returns only tenant B's rows.
     *
     * <p>The cursor carries no tenant. Isolation is in the mandatory client_id predicate.
     */
    @Test
    void findPage_cursorFromTenantA_replayedByTenantB_returnsTenantBRows() {
        String tenantA = "client-ta-" + UUID.randomUUID();
        UUID subA = insertSubscription(tenantA);
        String tenantB = "client-tb-" + UUID.randomUUID();
        UUID subB = insertSubscription(tenantB);

        Instant ts = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        insertDeliveryWithEca(tenantA, subA, ts);
        UUID bId = insertDeliveryWithEca(tenantB, subB, ts.plus(1, ChronoUnit.HOURS));

        // Get a cursor as tenant A
        DeliveryPage pageA = repo.findPage(tenantA, DeliveryPageQuery.unfiltered(), 1);
        Optional<String> cursorFromA = pageA.nextCursor();

        if (cursorFromA.isPresent()) {
            // Tenant B uses that cursor — should only return tenant B rows
            DeliveryPage pageB = repo.findPage(tenantB,
                    new DeliveryPageQuery(Optional.empty(), Optional.empty(), Optional.empty(), cursorFromA),
                    10);
            assertThat(pageB.deliveries().stream().map(Delivery::deliveryId))
                    .doesNotContainAnyElementsOf(pageA.deliveries().stream()
                            .map(Delivery::deliveryId).collect(Collectors.toList()));
        }
    }

    /** Test 14: malformed cursor propagates MalformedCursorException; does NOT return page 1. */
    @Test
    void findPage_malformedCursor_propagatesException_notPage1() {
        assertThatThrownBy(() -> repo.findPage(clientId,
                new DeliveryPageQuery(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of("not-a-valid-cursor!!!")),
                10))
                .isInstanceOf(DeliveryPageCursor.MalformedCursorException.class);
    }

    // -----------------------------------------------------------------------
    // Tests 15-16: plan assertions
    // -----------------------------------------------------------------------

    /** Test 15: EXPLAIN shows idx_deliveries_client_event_created_at; no seq scan. */
    @Test
    void findPage_planUsesNewIndex_noSeqScan() throws Exception {
        // Seed enough rows for the planner to prefer the index
        Instant base = Instant.now().minus(100, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 300; i++) {
            insertDeliveryWithEca(clientId, subscriptionId, base.plus(i, ChronoUnit.MINUTES));
        }
        jdbc.getJdbcTemplate().execute("ANALYZE deliveries");

        String planSql = "SELECT delivery_id, event_id, subscription_id, client_id, status, origin,"
                + " replayed_from, attempt_count, next_attempt_at, last_error,"
                + " delivered_at, event_created_at, trace_context"
                + " FROM deliveries WHERE client_id = '" + clientId.replace("'", "''") + "'"
                + " ORDER BY event_created_at DESC, delivery_id DESC LIMIT 10";

        String planJson = jdbc.getJdbcTemplate()
                .queryForObject("EXPLAIN (FORMAT JSON) " + planSql, String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(planJson);
        JsonNode plan = root.get(0).get("Plan");

        assertThat(usesIndex(plan, "idx_deliveries_client_event_created_at"))
                .as("plan must use idx_deliveries_client_event_created_at\n" + planJson).isTrue();
        assertThat(hasSeqScanOnDeliveries(plan))
                .as("plan must not seq scan deliveries\n" + planJson).isFalse();
    }

    /** Test 16: plan does NOT reference dropped index idx_deliveries_client_created_at. */
    @Test
    void findPage_planDoesNotReferenceDroppedIndex() throws Exception {
        jdbc.getJdbcTemplate().execute("ANALYZE deliveries");
        String planSql = "SELECT delivery_id FROM deliveries WHERE client_id = '"
                + clientId.replace("'", "''")
                + "' ORDER BY event_created_at DESC LIMIT 10";

        String planJson = jdbc.getJdbcTemplate()
                .queryForObject("EXPLAIN (FORMAT JSON) " + planSql, String.class);

        assertThat(planJson).doesNotContain("idx_deliveries_client_created_at");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertDeliveryWithEca(String cid, UUID sid, Instant eca) {
        return insertDeliveryWithEcaAndStatus(cid, sid, eca, "PENDING");
    }

    private UUID insertDeliveryWithEcaAndStatus(String cid, UUID sid, Instant eca, String status) {
        String eventId = "EVT-PAGE-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", cid));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, :eca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", cid)
                        .addValue("status", status)
                        .addValue("eca", OffsetDateTime.ofInstant(eca, ZoneOffset.UTC)));
        return deliveryId;
    }

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }

    private boolean usesIndex(JsonNode node, String indexName) {
        JsonNode n = node.get("Index Name");
        if (n != null && indexName.equals(n.asText())) return true;
        JsonNode plans = node.get("Plans");
        if (plans == null) return false;
        for (JsonNode child : plans) if (usesIndex(child, indexName)) return true;
        return false;
    }

    private boolean hasSeqScanOnDeliveries(JsonNode node) {
        JsonNode type = node.get("Node Type");
        JsonNode rel = node.get("Relation Name");
        if (type != null && "Seq Scan".equals(type.asText())
                && rel != null && "deliveries".equals(rel.asText())) return true;
        JsonNode plans = node.get("Plans");
        if (plans == null) return false;
        for (JsonNode child : plans) if (hasSeqScanOnDeliveries(child)) return true;
        return false;
    }
}
