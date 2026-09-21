package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Integration tests for {@link DeliveryPipelineJdbcRepository}: insert/findById round trips
 * and the {@code claimForProcessing} conditional-claim properties.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 * Fixtures for claim tests are inserted with plain SQL so a bug in {@code insert} cannot
 * silently pass a {@code claimForProcessing} test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryPipelineJdbcRepositoryTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void perTestFixtures() {
        clientId = "client-pipeline-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    // -----------------------------------------------------------------------
    // insert
    // -----------------------------------------------------------------------

    /** Test 1: round trip — every component is persisted and returned. */
    @Test
    void insert_roundTrip() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery d = buildDelivery(eventId, eventCreatedAt, Optional.of("traceparent-1"));

        Delivery persisted = repo.insert(d);

        assertThat(persisted.deliveryId()).isEqualTo(d.deliveryId());
        assertThat(persisted.eventId()).isEqualTo(d.eventId());
        assertThat(persisted.subscriptionId()).isEqualTo(d.subscriptionId());
        assertThat(persisted.clientId()).isEqualTo(d.clientId());
        assertThat(persisted.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(persisted.origin()).isEqualTo(DeliveryOrigin.INGEST);
        assertThat(persisted.replayedFrom()).isEmpty();
        assertThat(persisted.attemptCount()).isEqualTo(0);
        assertThat(persisted.nextAttemptAt()).isEmpty();
        assertThat(persisted.lastError()).isEmpty();
        assertThat(persisted.deliveredAt()).isEmpty();
        assertThat(persisted.eventCreatedAt()).isEqualTo(eventCreatedAt);
        assertThat(persisted.traceContext()).contains("traceparent-1");
    }

    /**
     * Test 2: event_created_at fidelity.
     *
     * <p>The event_created_at must be the event's own timestamp, not wall-clock insert time.
     * For a replay the two differ by weeks; a wrong binding would file the delivery under the
     * replay date instead of the event date (ADR-003 Amendment A4).
     */
    @Test
    void insert_eventCreatedAt_preservesEventTimestamp_notInsertTime() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Delivery d = buildDelivery(eventId, eventCreatedAt, Optional.empty());

        Delivery persisted = repo.insert(d);

        assertThat(persisted.eventCreatedAt()).isEqualTo(eventCreatedAt);

        // created_at (the row-insert time) is NOT equal to event_created_at
        Instant createdAt = jdbc.queryForObject(
                "SELECT created_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", d.deliveryId()),
                (rs, n) -> rs.getObject("created_at", OffsetDateTime.class).toInstant());
        assertThat(persisted.eventCreatedAt()).isNotEqualTo(createdAt);
        assertThat(persisted.eventCreatedAt()).isBefore(createdAt.minus(29, ChronoUnit.DAYS));
    }

    /** Test 3: trace_context round trips — populated and empty (not empty string). */
    @Test
    void insert_traceContext_roundTrips() {
        String eventId1 = insertEvent(clientId);
        String eventId2 = "EVT-TC2-" + UUID.randomUUID();
        insertEventWithId(eventId2, clientId);

        Delivery withTrace = buildDelivery(eventId1, Instant.now().truncatedTo(ChronoUnit.MICROS),
                Optional.of("00-aabbcc-ddeeff-01"));
        Delivery withoutTrace = buildDelivery(eventId2, Instant.now().truncatedTo(ChronoUnit.MICROS),
                Optional.empty());

        assertThat(repo.insert(withTrace).traceContext()).contains("00-aabbcc-ddeeff-01");

        Delivery persistedNoTrace = repo.insert(withoutTrace);
        assertThat(persistedNoTrace.traceContext()).isEmpty();
        assertThat(persistedNoTrace.traceContext()).isNotEqualTo(Optional.of(""));
    }

    /**
     * Test 4: idempotency guard propagates DuplicateKeyException.
     *
     * <p>Two inserts violating ADR-003 §2's partial unique index must throw —
     * swallowing it would make a double replay look like a success.
     */
    @Test
    void insert_duplicateKeyExceptionPropagates() {
        String eventId = insertEvent(clientId);
        Instant eca = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repo.insert(buildDelivery(eventId, eca, Optional.empty()));

        assertThatThrownBy(() -> repo.insert(buildDelivery(eventId, eca, Optional.empty())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // -----------------------------------------------------------------------
    // findById
    // -----------------------------------------------------------------------

    /** Test 5: absent row returns Optional.empty(). */
    @Test
    void findById_absentRow_returnsEmpty() {
        assertThat(repo.findById(UUID.randomUUID())).isEmpty();
    }

    /**
     * Test 6: a row belonging to a different client_id IS returned.
     *
     * <p>This is the cross-tenant pipeline read working as designed (ADR-007 Amendment E1).
     * Asserting it explicitly stops a later reviewer from "fixing" it by adding a tenant
     * predicate. The tenant-scoped read lives on DeliveryQueryRepositoryPort (TASK-004-13).
     */
    @Test
    void findById_crossTenantRead_succeeds_adr007E1() {
        String otherClientId = "client-other-" + UUID.randomUUID();
        UUID otherSub = insertSubscription(otherClientId);
        String eventId = insertEventForClient(otherClientId);
        UUID deliveryId = UUID.randomUUID();
        insertDeliveryRaw(deliveryId, eventId, otherSub, otherClientId, "PENDING");

        // findById on the pipeline port has no client_id filter by design (ADR-007 E1)
        assertThat(repo.findById(deliveryId)).isPresent();
    }

    // -----------------------------------------------------------------------
    // claimForProcessing
    // -----------------------------------------------------------------------

    /** Test 7: QUEUED row returns true; status becomes PROCESSING; updated_at advances. */
    @Test
    void claimForProcessing_queuedRow_returnsTrue() {
        UUID deliveryId = insertDeliveryRawReturningId("QUEUED");
        Instant before = readUpdatedAt(deliveryId);

        Instant now = Instant.now();
        boolean claimed = repo.claimForProcessing(deliveryId, now);

        assertThat(claimed).isTrue();
        assertThat(readStatus(deliveryId)).isEqualTo("PROCESSING");
        assertThat(readUpdatedAt(deliveryId)).isAfterOrEqualTo(before);
    }

    /**
     * Test 8: a second claim on an already-claimed row returns false and affects zero rows.
     *
     * <p>This is the double-send guard (ADR-002 §2.2 step 2). The returned {@code false}
     * is asserted directly, not only the persisted state.
     */
    @Test
    void claimForProcessing_secondClaim_returnsFalse_rowUnchanged() {
        UUID deliveryId = insertDeliveryRawReturningId("QUEUED");
        Instant now = Instant.now();
        assertThat(repo.claimForProcessing(deliveryId, now)).isTrue();

        Instant updatedAfterFirstClaim = readUpdatedAt(deliveryId);

        // Second claim on the now-PROCESSING row
        boolean secondClaim = repo.claimForProcessing(deliveryId, now.plusSeconds(1));

        assertThat(secondClaim).isFalse();
        assertThat(readUpdatedAt(deliveryId)).isEqualTo(updatedAfterFirstClaim);
    }

    /**
     * Test 9: every non-QUEUED status returns false and mutates nothing.
     */
    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "QUEUED", mode = EnumSource.Mode.EXCLUDE)
    void claimForProcessing_nonQueuedStatus_returnsFalse(DeliveryStatus status) {
        UUID deliveryId = insertDeliveryRawWithStatus(status);

        boolean result = repo.claimForProcessing(deliveryId, Instant.now());

        assertThat(result).isFalse();
        assertThat(readStatus(deliveryId)).isEqualTo(status.name());
    }

    /** Test 10: non-existent delivery_id returns false without throwing. */
    @Test
    void claimForProcessing_nonExistentId_returnsFalse() {
        assertThat(repo.claimForProcessing(UUID.randomUUID(), Instant.now())).isFalse();
    }

    /**
     * Test 11: after a successful claim, five specific columns are unchanged.
     *
     * <p>Ensures claimForProcessing writes only status and updated_at.
     */
    @Test
    void claimForProcessing_onlyStatusAndUpdatedAt_change() {
        UUID deliveryId = insertDeliveryRawReturningId("QUEUED");

        // Read pre-claim state
        Integer attemptCount = readIntColumn(deliveryId, "attempt_count");
        OffsetDateTime nextAttemptAt = readOdtColumn(deliveryId, "next_attempt_at");
        String lastError = readStringColumn(deliveryId, "last_error");
        OffsetDateTime deliveredAt = readOdtColumn(deliveryId, "delivered_at");
        OffsetDateTime eventCreatedAt = readOdtColumn(deliveryId, "event_created_at");

        repo.claimForProcessing(deliveryId, Instant.now());

        assertThat(readIntColumn(deliveryId, "attempt_count")).isEqualTo(attemptCount);
        assertThat(readOdtColumn(deliveryId, "next_attempt_at")).isEqualTo(nextAttemptAt);
        assertThat(readStringColumn(deliveryId, "last_error")).isEqualTo(lastError);
        assertThat(readOdtColumn(deliveryId, "delivered_at")).isEqualTo(deliveredAt);
        assertThat(readOdtColumn(deliveryId, "event_created_at")).isEqualTo(eventCreatedAt);
    }

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    private String insertEvent(String cid) {
        String eventId = "EVT-PIPE-" + UUID.randomUUID();
        insertEventWithId(eventId, cid);
        return eventId;
    }

    private String insertEventForClient(String cid) {
        String eventId = "EVT-PIPE-" + UUID.randomUUID();
        insertEventWithId(eventId, cid);
        return eventId;
    }

    private void insertEventWithId(String eventId, String cid) {
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", cid));
    }

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }

    private void insertDeliveryRaw(UUID deliveryId, String eventId, UUID sid, String cid, String status) {
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, now())",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("eid", eventId)
                        .addValue("sid", sid)
                        .addValue("cid", cid)
                        .addValue("status", status));
    }

    private UUID insertDeliveryRawReturningId(String status) {
        String eventId = insertEvent(clientId);
        UUID deliveryId = UUID.randomUUID();
        insertDeliveryRaw(deliveryId, eventId, subscriptionId, clientId, status);
        return deliveryId;
    }

    private UUID insertDeliveryRawWithStatus(DeliveryStatus status) {
        return insertDeliveryRawReturningId(status.name());
    }

    private Delivery buildDelivery(String eventId, Instant eventCreatedAt, Optional<String> traceContext) {
        return new Delivery(
                UUID.randomUUID(), eventId, subscriptionId, clientId,
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                eventCreatedAt, traceContext);
    }

    private String readStatus(UUID deliveryId) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), String.class);
    }

    private Instant readUpdatedAt(UUID deliveryId) {
        return jdbc.queryForObject("SELECT updated_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                (rs, n) -> rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private Integer readIntColumn(UUID deliveryId, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), Integer.class);
    }

    private OffsetDateTime readOdtColumn(UUID deliveryId, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                (rs, n) -> rs.getObject(col, OffsetDateTime.class));
    }

    private String readStringColumn(UUID deliveryId, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), String.class);
    }
}
