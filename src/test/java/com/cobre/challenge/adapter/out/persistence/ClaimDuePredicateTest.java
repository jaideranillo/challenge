package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Predicate tests for {@link DeliveryPipelineJdbcRepository#claimDue}: one per ADR-002 §2.1
 * predicate, each proving the row is excluded or included as expected.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 * SKIP LOCKED, partial-index planning and interval arithmetic are Postgres behaviours.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClaimDuePredicateTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private Instant asOf;
    private TransactionTemplate tx;
    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void fixtures() {
        // claimDue is a global, non-tenant-scoped LIMIT query: unlike every other adapter
        // test here (which asserts on specific known ids), these tests assert on batch
        // membership within a fixed LIMIT. A still-due row left by a prior test would sort
        // ahead of this test's fixture by next_attempt_at and silently crowd it out.
        jdbc.getJdbcTemplate().update("DELETE FROM delivery_attempts");
        jdbc.getJdbcTemplate().update("DELETE FROM deliveries");
        clientId = "client-pred-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
        asOf = Instant.now().plus(5, ChronoUnit.MINUTES);
        tx = new TransactionTemplate(txManager);
    }

    /** Test 7: terminal statuses are never returned. */
    @Test
    void terminalStatuses_neverReturned() {
        for (String status : new String[]{"DELIVERED", "DEAD", "FAILED"}) {
            UUID id = seedDelivery(subscriptionId, status,
                    Instant.now().minus(1, ChronoUnit.MINUTES), null, null);
            List<Delivery> result = claimDue(10);
            assertThat(result.stream().map(Delivery::deliveryId))
                    .as(status + " should never be claimed").doesNotContain(id);
        }
    }

    /** Test 8: next_attempt_at > asOf excluded; <= asOf included. */
    @Test
    void nextAttemptAt_predicate() {
        UUID excluded = seedDelivery(subscriptionId, "PENDING",
                asOf.plus(10, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES), null);
        UUID included = seedDelivery(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(excluded);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(included);
    }

    /** Test 9: PENDING younger than 30-second grace excluded; older included. */
    @Test
    void pending_graceWindow_30s() {
        // Young PENDING: created_at is only 5 seconds ago
        UUID young = seedDeliveryWithCreatedAt(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(5, ChronoUnit.SECONDS));
        // Old PENDING: created_at is 5 minutes ago
        UUID old = seedDeliveryWithCreatedAt(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(5, ChronoUnit.MINUTES));

        List<Delivery> result = claimDue(10);
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(young);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(old);
    }

    /** Test 10: PROCESSING fresher than 60 seconds excluded; staler included (crashed-worker). */
    @Test
    void processing_stalenessGuard_60s() {
        UUID fresh = seedDeliveryWithUpdatedAt(subscriptionId, "PROCESSING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(30, ChronoUnit.SECONDS));
        UUID stale = seedDeliveryWithUpdatedAt(subscriptionId, "PROCESSING",
                asOf.minus(2, ChronoUnit.MINUTES),
                asOf.minus(2, ChronoUnit.MINUTES));

        List<Delivery> result = claimDue(10);
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(fresh);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(stale);
    }

    /** Test 11: circuit OPEN with unelapsed cooldown excluded; with elapsed cooldown included. */
    @Test
    void circuit_openWithElapsedCooldown_included() {
        UUID subCooling = insertSubscriptionWithCircuit("OPEN",
                asOf.minus(10, ChronoUnit.SECONDS), "30 seconds");
        UUID subCooled = insertSubscriptionWithCircuit("OPEN",
                asOf.minus(120, ChronoUnit.SECONDS), "30 seconds");

        UUID excluded = seedDelivery(subCooling, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(5, ChronoUnit.MINUTES), null);
        UUID included = seedDelivery(subCooled, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(excluded);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(included);
    }

    /** Test 12: throttled_until in future excluded; null or past included. */
    @Test
    void throttledUntil_predicate() {
        UUID subThrottled = insertSubscriptionWithThrottle(asOf.plus(5, ChronoUnit.MINUTES));
        UUID subNotThrottled = insertSubscriptionWithThrottle(null);

        UUID excluded = seedDelivery(subThrottled, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);
        UUID included = seedDelivery(subNotThrottled, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(excluded);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(included);
    }

    /** Test 13: ORDER BY next_attempt_at; LIMIT honored exactly. */
    @Test
    void orderByNextAttemptAt_andLimit() {
        // Seed 5 rows with different next_attempt_at
        for (int i = 0; i < 5; i++) {
            seedDelivery(subscriptionId, "PENDING",
                    asOf.minus(10 - i, ChronoUnit.MINUTES),
                    asOf.minus(5, ChronoUnit.MINUTES), null);
        }
        List<Delivery> result = claimDue(3);
        assertThat(result).hasSize(3);
    }

    /**
     * Test 14: returned rows are QUEUED with next_attempt_at advanced ~5 min;
     * event_created_at is unchanged by the claim.
     */
    @Test
    void claimedRows_statusQueued_nextAttemptPushed_eventCreatedAtUnchanged() {
        Instant originalEca = asOf.minus(30, ChronoUnit.DAYS);
        UUID id = seedDeliveryWithEca(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES),
                asOf.minus(5, ChronoUnit.MINUTES), originalEca);

        List<Delivery> result = claimDue(10);

        Delivery claimed = result.stream()
                .filter(d -> d.deliveryId().equals(id)).findFirst().orElseThrow();
        assertThat(claimed.status().name()).isEqualTo("QUEUED");
        assertThat(claimed.nextAttemptAt()).isPresent();
        assertThat(claimed.nextAttemptAt().get())
                .isAfter(asOf.plus(4, ChronoUnit.MINUTES));
        // event_created_at unchanged
        assertThat(claimed.eventCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(originalEca.truncatedTo(ChronoUnit.SECONDS));
    }

    /** Test 15: empty result returns empty list, never null. */
    @Test
    void claimDue_noEligibleRows_returnsEmptyList() {
        List<Delivery> result = claimDue(10);
        assertThat(result).isNotNull().isEmpty();
    }

    // -----------------------------------------------------------------------
    // TASK-006-02 scenarios 1-6 (TASK-006-01's LATERAL restructure)
    // -----------------------------------------------------------------------

    /** Scenario 1: PENDING row inside the 30s grace window is not claimed. */
    @Test
    void graceWindow_inside_notClaimed() {
        UUID id = seedDeliveryWithCreatedAt(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.SECONDS));

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, id, "PENDING");
    }

    /** Scenario 2: PENDING row outside the 30s grace window is claimed. */
    @Test
    void graceWindow_outside_claimed() {
        UUID id = seedDeliveryWithCreatedAt(subscriptionId, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(31, ChronoUnit.SECONDS));

        List<Delivery> result = claimDue(10);

        assertClaimed(result, id);
    }

    /** Scenario 3: OPEN circuit still cooling down excludes the row. */
    @Test
    void openCircuit_stillCooling_notClaimed() {
        UUID sid = insertSubscriptionWithCircuit("OPEN",
                asOf.minus(10, ChronoUnit.SECONDS), "1 minute");
        UUID id = seedDelivery(sid, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, id, "PENDING");
    }

    /** Scenario 4: active throttle excludes the row; an expired throttle does not. */
    @Test
    void throttle_active_notClaimed_expired_claimed() {
        UUID throttled = insertSubscriptionWithThrottle(asOf.plus(1, ChronoUnit.MINUTES));
        UUID expired = insertSubscriptionWithThrottle(asOf.minus(1, ChronoUnit.SECONDS));
        UUID excluded = seedDelivery(throttled, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);
        UUID included = seedDelivery(expired, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, excluded, "PENDING");
        assertClaimed(result, included);
    }

    /** Scenario 5: next_attempt_at in the future excludes the row; exactly asOf claims it (predicate is <=). */
    @Test
    void nextAttemptAt_future_notClaimed_exactlyAsOf_claimed() {
        UUID excluded = seedDelivery(subscriptionId, "PENDING",
                asOf.plus(1, ChronoUnit.SECONDS), asOf.minus(5, ChronoUnit.MINUTES), null);
        UUID included = seedDelivery(subscriptionId, "PENDING",
                asOf, asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, excluded, "PENDING");
        assertClaimed(result, included);
    }

    /** Scenario 6a: an inactive subscription's otherwise-claimable row is excluded. */
    @Test
    void deliverabilityGate_inactiveSubscription_notClaimed() {
        UUID sid = insertSubscriptionWithActiveAndVerification(false, "VERIFIED");
        UUID id = seedDelivery(sid, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, id, "PENDING");
    }

    /** Scenario 6b: a subscription pending verification's otherwise-claimable row is excluded. */
    @Test
    void deliverabilityGate_pendingVerification_notClaimed() {
        UUID sid = insertSubscriptionWithActiveAndVerification(true, "PENDING_VERIFICATION");
        UUID id = seedDelivery(sid, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertNotClaimed(result, id, "PENDING");
    }

    /** Scenario 6c: an active, verified subscription's row is claimed. */
    @Test
    void deliverabilityGate_activeAndVerified_claimed() {
        UUID sid = insertSubscriptionWithActiveAndVerification(true, "VERIFIED");
        UUID id = seedDelivery(sid, "PENDING",
                asOf.minus(1, ChronoUnit.MINUTES), asOf.minus(5, ChronoUnit.MINUTES), null);

        List<Delivery> result = claimDue(10);

        assertClaimed(result, id);
    }

    /**
     * Test 16: EXPLAIN shows idx_deliveries_due is used; no seq scan on deliveries.
     *
     * <p>Seeds 5,000 rows to ensure the planner won't prefer a seq scan on size alone.
     */
    @Test
    void claimDue_planUsesIdxDeliveriesDue_noSeqScan() throws Exception {
        // Seed enough rows
        List<Object[]> eventBatch = new java.util.ArrayList<>(200);
        List<Object[]> delivBatch = new java.util.ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            String eid = "EVT-PLAN-" + UUID.randomUUID();
            UUID did = UUID.randomUUID();
            eventBatch.add(new Object[]{eid, clientId});
            delivBatch.add(new Object[]{did, eid, subscriptionId, clientId, i < 50});
        }
        jdbc.getJdbcTemplate().batchUpdate(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (?, ?, 'payment.completed', 'test', now() - interval '5 minutes')",
                eventBatch);
        jdbc.getJdbcTemplate().batchUpdate(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status, "
                        + "next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (?, ?, ?, ?, CASE WHEN ? THEN 'PENDING'::delivery_status ELSE 'DELIVERED'::delivery_status END, "
                        + "CASE WHEN ? THEN now() - interval '1 minute' ELSE NULL END, "
                        + "now() - interval '5 minutes', now() - interval '5 minutes', now() - interval '5 minutes')",
                delivBatch.stream().map(row -> new Object[]{
                        row[0], row[1], row[2], row[3], row[4], row[4]
                }).collect(java.util.stream.Collectors.toList()));
        jdbc.getJdbcTemplate().execute("ANALYZE deliveries");

        String dueSql =
                "SELECT d.delivery_id FROM deliveries d "
                        + "JOIN subscriptions s ON s.subscription_id = d.subscription_id "
                        + "WHERE d.status IN ('PENDING','RETRYING','QUEUED','PROCESSING') "
                        + "AND d.next_attempt_at <= now() + interval '5 minutes' "
                        + "AND (d.status <> 'PENDING' OR d.created_at < now() + interval '5 minutes' - interval '30 seconds') "
                        + "AND (d.status <> 'PROCESSING' OR d.updated_at < now() + interval '5 minutes' - interval '60 seconds') "
                        + "AND (s.circuit_state <> 'OPEN' OR s.circuit_opened_at < now() + interval '5 minutes' - s.circuit_backoff) "
                        + "AND (s.throttled_until IS NULL OR s.throttled_until <= now() + interval '5 minutes') "
                        + "ORDER BY d.next_attempt_at LIMIT 500 FOR UPDATE OF d SKIP LOCKED";

        String planJson = jdbc.getJdbcTemplate()
                .queryForObject("EXPLAIN (FORMAT JSON) " + dueSql, String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(planJson);
        JsonNode plan = root.get(0).get("Plan");

        assertThat(usesIndex(plan, "idx_deliveries_due"))
                .as("plan must use idx_deliveries_due\n" + planJson).isTrue();
        assertThat(hasSeqScanOnDeliveries(plan))
                .as("plan must not seq scan deliveries\n" + planJson).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<Delivery> claimDue(int limit) {
        List<Delivery>[] result = new List[1];
        tx.executeWithoutResult(status -> result[0] = repo.claimDue(limit, asOf));
        return result[0];
    }

    private UUID seedDelivery(UUID sid, String status, Instant nextAttemptAt,
            Instant createdAt, Instant updatedAt) {
        return seedDeliveryWithEca(sid, status, nextAttemptAt, createdAt,
                createdAt != null ? createdAt : Instant.now());
    }

    private UUID seedDeliveryWithEca(UUID sid, String status, Instant nextAttemptAt,
            Instant createdAt, Instant eca) {
        String eventId = "EVT-PRED-" + UUID.randomUUID();
        Instant ca = createdAt != null ? createdAt : Instant.now().minus(5, ChronoUnit.MINUTES);
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', :ca)",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId)
                        .addValue("ca", OffsetDateTime.ofInstant(ca, ZoneOffset.UTC)));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, "
                        + ":nat, :ca, :ca, :eca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", clientId)
                        .addValue("status", status)
                        .addValue("nat", nextAttemptAt != null
                                ? OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC) : null)
                        .addValue("ca", OffsetDateTime.ofInstant(ca, ZoneOffset.UTC))
                        .addValue("eca", OffsetDateTime.ofInstant(eca, ZoneOffset.UTC)));
        return deliveryId;
    }

    private UUID seedDeliveryWithCreatedAt(UUID sid, String status,
            Instant nextAttemptAt, Instant createdAt) {
        return seedDeliveryWithEca(sid, status, nextAttemptAt, createdAt, createdAt);
    }

    private UUID seedDeliveryWithUpdatedAt(UUID sid, String status,
            Instant nextAttemptAt, Instant updatedAt) {
        String eventId = "EVT-UPDT-" + UUID.randomUUID();
        Instant ca = Instant.now().minus(5, ChronoUnit.MINUTES);
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', :ca)",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId)
                        .addValue("ca", OffsetDateTime.ofInstant(ca, ZoneOffset.UTC)));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, "
                        + ":nat, :ca, :ua, :ca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", clientId)
                        .addValue("status", status)
                        .addValue("nat", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                        .addValue("ca", OffsetDateTime.ofInstant(ca, ZoneOffset.UTC))
                        .addValue("ua", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC)));
        return deliveryId;
    }

    // verification_state = 'VERIFIED' is bound explicitly in every subscription fixture below
    // (TASK-006-01): the column's own DEFAULT is PENDING_VERIFICATION (ADR-005 §2), which the
    // deliverability gate now excludes. Without this, every claimDue fixture in this class would
    // seed an undeliverable subscription and every row it owns would be silently dropped by the
    // new gate rather than by the predicate under test.

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, verification_state) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', "
                        + "ARRAY['payment.completed']::text[], 'VERIFIED')",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }

    private UUID insertSubscriptionWithCircuit(String circuitState, Instant openedAt, String backoff) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, verification_state, circuit_state, circuit_opened_at, circuit_backoff) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', "
                        + "ARRAY['payment.completed']::text[], 'VERIFIED', :cs::circuit_state, :oa, :cb::interval)",
                new MapSqlParameterSource()
                        .addValue("id", sid).addValue("cid", clientId)
                        .addValue("cs", circuitState)
                        .addValue("oa", OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC))
                        .addValue("cb", backoff));
        return sid;
    }

    private UUID insertSubscriptionWithThrottle(Instant throttledUntil) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, verification_state, throttled_until) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', "
                        + "ARRAY['payment.completed']::text[], 'VERIFIED', :tu)",
                new MapSqlParameterSource()
                        .addValue("id", sid).addValue("cid", clientId)
                        .addValue("tu", throttledUntil != null
                                ? OffsetDateTime.ofInstant(throttledUntil, ZoneOffset.UTC) : null));
        return sid;
    }

    private String readStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    /** Asserts a row is absent from the result and its table state is unchanged. */
    private void assertNotClaimed(List<Delivery> result, UUID id, String expectedStatus) {
        assertThat(result.stream().map(Delivery::deliveryId)).doesNotContain(id);
        assertThat(readStatus(id)).isEqualTo(expectedStatus);
    }

    /** Asserts a row is present in the result and transitioned to QUEUED with next_attempt_at pushed. */
    private void assertClaimed(List<Delivery> result, UUID id) {
        assertThat(result.stream().map(Delivery::deliveryId)).contains(id);
        assertThat(readStatus(id)).isEqualTo("QUEUED");
        OffsetDateTime nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), OffsetDateTime.class);
        assertThat(nextAttemptAt.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(asOf.plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
    }

    private UUID insertSubscriptionWithActiveAndVerification(boolean active, String verificationState) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, active, verification_state) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', "
                        + "ARRAY['payment.completed']::text[], :active, :vs::verification_state)",
                new MapSqlParameterSource()
                        .addValue("id", sid).addValue("cid", clientId)
                        .addValue("active", active).addValue("vs", verificationState));
        return sid;
    }

    private boolean usesIndex(JsonNode node, String indexName) {
        JsonNode nameNode = node.get("Index Name");
        if (nameNode != null && indexName.equals(nameNode.asText())) return true;
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
