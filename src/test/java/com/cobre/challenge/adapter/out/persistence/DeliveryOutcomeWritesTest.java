package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Integration tests for the four outcome writes on {@link DeliveryPipelineJdbcRepository}.
 *
 * <p>Fixtures inserted with plain SQL. Real Postgres via {@link TestcontainersConfiguration}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryOutcomeWritesTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void perTestFixtures() {
        clientId = "client-outcome-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    // -----------------------------------------------------------------------
    // markDelivered
    // -----------------------------------------------------------------------

    @Test
    void markDelivered_processingRow_returnsTrue_setsColumns() {
        UUID id = insertDelivery("PROCESSING");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(repo.markDelivered(id, now)).isTrue();
        assertThat(readStatus(id)).isEqualTo("DELIVERED");
        assertThat(readOdt(id, "delivered_at")).isNotNull();
        assertThat(readOdt(id, "next_attempt_at")).isNull();
        assertThat(readOdt(id, "updated_at")).isNotNull();
        // event_created_at unchanged (immutability)
        assertThat(readOdt(id, "event_created_at")).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "PROCESSING", mode = EnumSource.Mode.EXCLUDE)
    void markDelivered_nonProcessing_returnsFalse_noChange(DeliveryStatus status) {
        UUID id = insertDelivery(status.name());
        assertThat(repo.markDelivered(id, Instant.now())).isFalse();
        assertThat(readStatus(id)).isEqualTo(status.name());
    }

    @Test
    void markDelivered_nonExistentId_returnsFalse() {
        assertThat(repo.markDelivered(UUID.randomUUID(), Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // scheduleRetry
    // -----------------------------------------------------------------------

    @Test
    void scheduleRetry_processingRow_returnsTrue_setsColumns() {
        UUID id = insertDelivery("PROCESSING");
        Instant next = Instant.now().plus(60, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(repo.scheduleRetry(id, next, "timeout", now)).isTrue();
        assertThat(readStatus(id)).isEqualTo("RETRYING");
        assertThat(readInt(id, "attempt_count")).isEqualTo(1);
        assertThat(readString(id, "last_error")).isEqualTo("timeout");
        assertThat(readOdt(id, "next_attempt_at")).isNotNull();
        assertThat(readOdt(id, "updated_at").toInstant()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "PROCESSING", mode = EnumSource.Mode.EXCLUDE)
    void scheduleRetry_nonProcessing_returnsFalse_noChange(DeliveryStatus status) {
        UUID id = insertDelivery(status.name());
        assertThat(repo.scheduleRetry(id, Instant.now(), "err", Instant.now())).isFalse();
        assertThat(readStatus(id)).isEqualTo(status.name());
    }

    @Test
    void scheduleRetry_nonExistentId_returnsFalse() {
        assertThat(repo.scheduleRetry(UUID.randomUUID(), Instant.now(), "err", Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // markDead
    // -----------------------------------------------------------------------

    @Test
    void markDead_processingRow_returnsTrue_setsColumns() {
        UUID id = insertDelivery("PROCESSING");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(repo.markDead(id, "non-retryable", now)).isTrue();
        assertThat(readStatus(id)).isEqualTo("DEAD");
        assertThat(readOdt(id, "next_attempt_at")).isNull();
        assertThat(readString(id, "last_error")).isEqualTo("non-retryable");
        assertThat(readOdt(id, "updated_at").toInstant()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "PROCESSING", mode = EnumSource.Mode.EXCLUDE)
    void markDead_nonProcessing_returnsFalse_noChange(DeliveryStatus status) {
        UUID id = insertDelivery(status.name());
        assertThat(repo.markDead(id, "err", Instant.now())).isFalse();
        assertThat(readStatus(id)).isEqualTo(status.name());
    }

    @Test
    void markDead_nonExistentId_returnsFalse() {
        assertThat(repo.markDead(UUID.randomUUID(), "err", Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // markFailed
    // -----------------------------------------------------------------------

    @Test
    void markFailed_queuedRow_returnsTrue() {
        UUID id = insertDelivery("QUEUED");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(repo.markFailed(id, "dlq", now)).isTrue();
        assertThat(readStatus(id)).isEqualTo("FAILED");
        assertThat(readString(id, "last_error")).isEqualTo("dlq");
        assertThat(readOdt(id, "updated_at").toInstant()).isEqualTo(now);
    }

    @Test
    void markFailed_processingRow_returnsTrue() {
        UUID id = insertDelivery("PROCESSING");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(repo.markFailed(id, "dlq", now)).isTrue();
        assertThat(readStatus(id)).isEqualTo("FAILED");
        assertThat(readString(id, "last_error")).isEqualTo("dlq");
        assertThat(readOdt(id, "updated_at").toInstant()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"QUEUED", "PROCESSING"}, mode = EnumSource.Mode.EXCLUDE)
    void markFailed_nonQueuedOrProcessing_returnsFalse_noChange(DeliveryStatus status) {
        UUID id = insertDelivery(status.name());
        assertThat(repo.markFailed(id, "dlq", Instant.now())).isFalse();
        assertThat(readStatus(id)).isEqualTo(status.name());
    }

    @Test
    void markFailed_nonExistentId_returnsFalse() {
        assertThat(repo.markFailed(UUID.randomUUID(), "dlq", Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // Cross-cutting properties
    // -----------------------------------------------------------------------

    /** scheduleRetry called twice advances attempt_count by exactly 2. */
    @Test
    void scheduleRetry_twiceViaRe_claim_advancesAttemptCountBy2() {
        UUID id = insertDelivery("PROCESSING");
        Instant now = Instant.now();
        repo.scheduleRetry(id, now.plus(30, ChronoUnit.SECONDS), "err1", now);
        // Re-claim to PROCESSING
        jdbc.update("UPDATE deliveries SET status = 'PROCESSING'::delivery_status WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id));
        repo.scheduleRetry(id, now.plus(60, ChronoUnit.SECONDS), "err2", now.plus(1, ChronoUnit.SECONDS));
        assertThat(readInt(id, "attempt_count")).isEqualTo(2);
    }

    /** markFailed on a DELIVERED row returns false and leaves the row DELIVERED. */
    @Test
    void markFailed_deliveredRow_returnsFalse_rowUnchanged() {
        UUID id = insertDelivery("DELIVERED");
        assertThat(repo.markFailed(id, "late-dlq", Instant.now())).isFalse();
        assertThat(readStatus(id)).isEqualTo("DELIVERED");
    }

    /** After each of the four transitions, event_created_at and created_at are unchanged. */
    @Test
    void allFourOutcomes_eventCreatedAtAndCreatedAt_neverModified() {
        UUID d1 = insertDelivery("PROCESSING");
        UUID d2 = insertDelivery("PROCESSING");
        UUID d3 = insertDelivery("PROCESSING");
        UUID d4 = insertDelivery("QUEUED");

        Instant eca1 = readOdt(d1, "event_created_at").toInstant();
        Instant eca2 = readOdt(d2, "event_created_at").toInstant();
        Instant eca3 = readOdt(d3, "event_created_at").toInstant();
        Instant eca4 = readOdt(d4, "event_created_at").toInstant();
        Instant ca1 = readOdt(d1, "created_at").toInstant();
        Instant ca2 = readOdt(d2, "created_at").toInstant();
        Instant ca3 = readOdt(d3, "created_at").toInstant();
        Instant ca4 = readOdt(d4, "created_at").toInstant();

        repo.markDelivered(d1, Instant.now());
        repo.scheduleRetry(d2, Instant.now(), "err", Instant.now());
        repo.markDead(d3, "err", Instant.now());
        repo.markFailed(d4, "err", Instant.now());

        assertThat(readOdt(d1, "event_created_at").toInstant()).isEqualTo(eca1);
        assertThat(readOdt(d2, "event_created_at").toInstant()).isEqualTo(eca2);
        assertThat(readOdt(d3, "event_created_at").toInstant()).isEqualTo(eca3);
        assertThat(readOdt(d4, "event_created_at").toInstant()).isEqualTo(eca4);
        assertThat(readOdt(d1, "created_at").toInstant()).isEqualTo(ca1);
        assertThat(readOdt(d2, "created_at").toInstant()).isEqualTo(ca2);
        assertThat(readOdt(d3, "created_at").toInstant()).isEqualTo(ca3);
        assertThat(readOdt(d4, "created_at").toInstant()).isEqualTo(ca4);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertDelivery(String status) {
        String eventId = "EVT-OUT-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, now())",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", subscriptionId).addValue("cid", clientId)
                        .addValue("status", status));
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

    private String readStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private Integer readInt(UUID id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
    }

    private String readString(UUID id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private OffsetDateTime readOdt(UUID id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getObject(col, OffsetDateTime.class));
    }
}
