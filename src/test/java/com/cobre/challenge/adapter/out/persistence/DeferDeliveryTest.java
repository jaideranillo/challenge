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
 * Integration tests for {@link DeliveryPipelineJdbcRepository#deferDelivery}.
 *
 * <p>The defining property of deferDelivery is what it must NOT do (no attempt occurred).
 * Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeferDeliveryTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void perTestFixtures() {
        clientId = "client-defer-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    /** Test 1: QUEUED row returns true; next_attempt_at advances; updated_at changes. */
    @Test
    void deferDelivery_queuedRow_returnsTrue_nextAttemptUpdated() {
        UUID id = insertDelivery("QUEUED");
        Instant nextAttempt = Instant.now().plus(30, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);
        Instant before = readOdt(id, "updated_at").toInstant();

        assertThat(repo.deferDelivery(id, nextAttempt)).isTrue();

        OffsetDateTime stored = readOdt(id, "next_attempt_at");
        assertThat(stored).isNotNull();
        assertThat(stored.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(nextAttempt.truncatedTo(ChronoUnit.SECONDS));
        assertThat(readOdt(id, "updated_at").toInstant()).isAfterOrEqualTo(before);
    }

    /**
     * Test 2: the must-not-touch assertion.
     *
     * <p>After a successful defer: status stays QUEUED, attempt_count unchanged,
     * last_error unchanged, delivered_at unchanged, event_created_at unchanged, created_at unchanged.
     */
    @Test
    void deferDelivery_doesNotTouchForbiddenColumns() {
        UUID id = insertDelivery("QUEUED");
        Integer attemptCountBefore = readInt(id, "attempt_count");
        String lastErrorBefore = readString(id, "last_error");
        OffsetDateTime deliveredAtBefore = readOdt(id, "delivered_at");
        OffsetDateTime ecaBefore = readOdt(id, "event_created_at");
        OffsetDateTime createdAtBefore = readOdt(id, "created_at");

        repo.deferDelivery(id, Instant.now().plus(10, ChronoUnit.SECONDS));

        assertThat(readStatus(id)).isEqualTo("QUEUED");
        assertThat(readInt(id, "attempt_count")).isEqualTo(attemptCountBefore);
        assertThat(readString(id, "last_error")).isEqualTo(lastErrorBefore);
        assertThat(readOdt(id, "delivered_at")).isEqualTo(deliveredAtBefore);
        assertThat(readOdt(id, "event_created_at")).isEqualTo(ecaBefore);
        assertThat(readOdt(id, "created_at")).isEqualTo(createdAtBefore);
    }

    /** Test 3: no delivery_attempts row exists after the defer. */
    @Test
    void deferDelivery_noDeliveryAttemptsRow() {
        UUID id = insertDelivery("QUEUED");
        repo.deferDelivery(id, Instant.now().plus(10, ChronoUnit.SECONDS));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        assertThat(count).isEqualTo(0);
    }

    /** Test 4: every non-QUEUED status returns false and mutates nothing. */
    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "QUEUED", mode = EnumSource.Mode.EXCLUDE)
    void deferDelivery_nonQueuedStatus_returnsFalse_noChange(DeliveryStatus status) {
        UUID id = insertDelivery(status.name());
        assertThat(repo.deferDelivery(id, Instant.now().plus(10, ChronoUnit.SECONDS))).isFalse();
        assertThat(readStatus(id)).isEqualTo(status.name());
    }

    /** Test 5: non-existent id returns false without throwing. */
    @Test
    void deferDelivery_nonExistentId_returnsFalse() {
        assertThat(repo.deferDelivery(UUID.randomUUID(), Instant.now().plus(10, ChronoUnit.SECONDS))).isFalse();
    }

    /**
     * Test 6: two consecutive defers both return true; attempt_count stays 0.
     *
     * <p>Sustained-bulkhead-pressure case (ADR-006 §1.1). Each defer must not increment
     * attempt_count or a sequence of deferrals would burn the retry budget.
     */
    @Test
    void deferDelivery_twiceConsecutively_bothReturnTrue_attemptCountUnchanged() {
        UUID id = insertDelivery("QUEUED");
        Instant t1 = Instant.now().plus(10, ChronoUnit.SECONDS);
        Instant t2 = Instant.now().plus(20, ChronoUnit.SECONDS);

        assertThat(repo.deferDelivery(id, t1)).isTrue();
        assertThat(repo.deferDelivery(id, t2)).isTrue();
        assertThat(readInt(id, "attempt_count")).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertDelivery(String status) {
        String eventId = "EVT-DEFER-" + UUID.randomUUID();
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
