package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scenario 7 (effective in-flight cap) and scenario 8 (starvation regression) for
 * {@link DeliveryPipelineJdbcRepository#claimDue}, split from {@link ClaimDuePredicateTest}
 * because of the fixture volume scenario 8 requires (TASK-006-02).
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClaimDueInFlightCapTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private Instant asOf;
    private TransactionTemplate tx;
    private String clientId;

    @BeforeEach
    void fixtures() {
        // Same isolation requirement as ClaimDuePredicateTest: a leftover due row from a prior
        // test would sort ahead of this test's fixture and distort the cap arithmetic.
        jdbc.getJdbcTemplate().update("DELETE FROM delivery_attempts");
        jdbc.getJdbcTemplate().update("DELETE FROM deliveries");
        clientId = "client-cap-" + UUID.randomUUID();
        asOf = Instant.now().plus(5, ChronoUnit.MINUTES);
        tx = new TransactionTemplate(txManager);
    }

    /** Scenario 7a: CLOSED circuit, max_concurrency=2, four due rows, nothing in flight: exactly 2 claimed. */
    @Test
    void cap_closedCircuit_nothingInFlight_admitsExactlyMaxConcurrency() {
        UUID sid = insertSubscription(2, "CLOSED", null, null);
        List<UUID> due = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            due.add(seedDelivery(sid, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES)));
        }

        List<Delivery> result = claimDue(10);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Delivery::deliveryId)).isSubsetOf(due);
    }

    /** Scenario 7b: same, but 1 fresh PROCESSING row already in flight consumes the cap: exactly 1 claimed. */
    @Test
    void cap_closedCircuit_freshInFlight_consumesCap() {
        UUID sid = insertSubscription(2, "CLOSED", null, null);
        for (int i = 0; i < 4; i++) {
            seedDelivery(sid, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
        }
        seedDeliveryProcessing(sid, asOf.minus(1, ChronoUnit.MINUTES), asOf);

        List<Delivery> result = claimDue(10);

        assertThat(result).hasSize(1);
    }

    /** Scenario 7c: a stale in-flight PROCESSING row is itself a candidate and does not consume the cap. */
    @Test
    void cap_closedCircuit_staleInFlight_reclaimedNotConsumingCap() {
        UUID sid = insertSubscription(2, "CLOSED", null, null);
        for (int i = 0; i < 4; i++) {
            seedDelivery(sid, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
        }
        // Most overdue of all candidates, so it sorts first and is unambiguously in the claimed set.
        UUID staleProcessing = seedDeliveryProcessing(sid,
                asOf.minus(10, ChronoUnit.MINUTES), asOf.minus(90, ChronoUnit.SECONDS));

        List<Delivery> result = claimDue(10);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Delivery::deliveryId)).contains(staleProcessing);
        assertThat(readStatus(staleProcessing)).isEqualTo("QUEUED");
    }

    /** Scenario 7d: HALF_OPEN circuit admits exactly one probe regardless of max_concurrency. */
    @Test
    void cap_halfOpenCircuit_admitsExactlyOneProbe() {
        UUID sid = insertSubscription(10, "HALF_OPEN", null, null);
        for (int i = 0; i < 3; i++) {
            seedDelivery(sid, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
        }

        List<Delivery> result = claimDue(10);

        assertThat(result).hasSize(1);
    }

    /** Scenario 7e: OPEN circuit whose cooldown has elapsed admits exactly one probe, before any promotion write. */
    @Test
    void cap_openCircuitCooldownElapsed_admitsExactlyOneProbe() {
        UUID sid = insertSubscription(10, "OPEN", asOf.minus(2, ChronoUnit.MINUTES), "1 minute");
        for (int i = 0; i < 3; i++) {
            seedDelivery(sid, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
        }

        List<Delivery> result = claimDue(10);

        assertThat(result).hasSize(1);
    }

    /** Scenario 7f: the cap is per subscription, not global across the batch. */
    @Test
    void cap_isPerSubscription_notGlobal() {
        UUID sidA = insertSubscription(1, "CLOSED", null, null);
        UUID sidB = insertSubscription(1, "CLOSED", null, null);
        for (int i = 0; i < 2; i++) {
            seedDelivery(sidA, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
            seedDelivery(sidB, "PENDING",
                    asOf.minus(5 + i, ChronoUnit.MINUTES), asOf.minus(10, ChronoUnit.MINUTES));
        }

        List<Delivery> result = claimDue(10);

        assertThat(result.stream().map(Delivery::subscriptionId)).containsExactlyInAnyOrder(sidA, sidB);
    }

    /**
     * Scenario 8: a saturated subscription (10,000 due rows, max_concurrency=10) must not crowd
     * out a quiet subscription's single due row, even though a naive global ranking (by
     * next_attempt_at) would place the quiet row last. This fails on the pre-LATERAL shape
     * (global candidate pool + global LIMIT, cap applied as a post-filter) and passes on the
     * per-subscription LATERAL shape (TASK-006-01).
     */
    @Test
    void saturatedSubscriptionDoesNotStarveQuietSubscription() {
        UUID saturated = insertSubscription(10, "CLOSED", null, null);
        UUID quiet = insertSubscription(10, "CLOSED", null, null);

        batchSeedDue(saturated, 10_000, asOf.minus(30, ChronoUnit.MINUTES));
        UUID quietDelivery = seedDelivery(quiet, "PENDING",
                asOf.minus(1, ChronoUnit.SECONDS), asOf.minus(10, ChronoUnit.MINUTES));

        List<Delivery> result = claimDue(500);

        List<UUID> saturatedClaimed = result.stream()
                .filter(d -> d.subscriptionId().equals(saturated))
                .map(Delivery::deliveryId)
                .collect(Collectors.toList());
        assertThat(result.stream().map(Delivery::deliveryId)).contains(quietDelivery);
        assertThat(saturatedClaimed).hasSizeLessThanOrEqualTo(10);
        assertThat(readStatus(quietDelivery)).isEqualTo("QUEUED");
        OffsetDateTime nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", quietDelivery), OffsetDateTime.class);
        assertThat(nextAttemptAt.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(asOf.plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<Delivery> claimDue(int limit) {
        List<Delivery>[] result = new List[1];
        tx.executeWithoutResult(status -> result[0] = repo.claimDue(limit, asOf));
        return result[0];
    }

    /** Batch-inserts count due PENDING rows on one subscription in a single round trip each table. */
    private void batchSeedDue(UUID sid, int count, Instant createdAt) {
        List<Object[]> events = new ArrayList<>(count);
        List<Object[]> deliveries = new ArrayList<>(count);
        OffsetDateTime createdAtOdt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            String eventId = "EVT-SAT-" + UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            events.add(new Object[]{eventId, clientId, createdAtOdt});
            // All older than the saturated subscription's quiet counterpart, so a naive global
            // ORDER BY next_attempt_at ranks every one of these ahead of the quiet row.
            OffsetDateTime nextAttemptAt = OffsetDateTime.ofInstant(
                    asOf.minus(60 - (i % 30), ChronoUnit.MINUTES), ZoneOffset.UTC);
            deliveries.add(new Object[]{deliveryId, eventId, sid, clientId, nextAttemptAt, createdAtOdt});
        }
        jdbc.getJdbcTemplate().batchUpdate(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (?, ?, 'payment.completed', 'test', ?)",
                events);
        jdbc.getJdbcTemplate().batchUpdate(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status, "
                        + "next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING'::delivery_status, ?, ?, ?, ?)",
                deliveries.stream()
                        .map(row -> new Object[]{row[0], row[1], row[2], row[3], row[4], row[5], row[5], row[5]})
                        .collect(Collectors.toList()));
    }

    private UUID seedDelivery(UUID sid, String status, Instant nextAttemptAt, Instant createdAt) {
        String eventId = "EVT-CAP-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', :ca)",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId)
                        .addValue("ca", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :status::delivery_status, :nat, :ca, :ca, :ca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", clientId)
                        .addValue("status", status)
                        .addValue("nat", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                        .addValue("ca", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
        return deliveryId;
    }

    /** Seeds a PROCESSING row with an explicit next_attempt_at and updated_at (in-flight fixture). */
    private UUID seedDeliveryProcessing(UUID sid, Instant nextAttemptAt, Instant updatedAt) {
        String eventId = "EVT-CAP-PROC-" + UUID.randomUUID();
        Instant createdAt = asOf.minus(10, ChronoUnit.MINUTES);
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', :ca)",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId)
                        .addValue("ca", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, 'PROCESSING'::delivery_status, :nat, :ca, :ua, :ca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", clientId)
                        .addValue("nat", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                        .addValue("ca", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                        .addValue("ua", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC)));
        return deliveryId;
    }

    /** verification_state = 'VERIFIED' bound explicitly (TASK-006-01): the column's own DEFAULT is
     * PENDING_VERIFICATION (ADR-005 §2), which the deliverability gate excludes. */
    private UUID insertSubscription(int maxConcurrency, String circuitState, Instant circuitOpenedAt, String circuitBackoff) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, verification_state, max_concurrency, circuit_state, "
                        + "circuit_opened_at, circuit_backoff) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', "
                        + "ARRAY['payment.completed']::text[], 'VERIFIED', :mc, :cs::circuit_state, :oa, :cb::interval)",
                new MapSqlParameterSource()
                        .addValue("id", sid).addValue("cid", clientId).addValue("mc", maxConcurrency)
                        .addValue("cs", circuitState)
                        .addValue("oa", circuitOpenedAt != null
                                ? OffsetDateTime.ofInstant(circuitOpenedAt, ZoneOffset.UTC) : null)
                        .addValue("cb", circuitBackoff));
        return sid;
    }

    private String readStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }
}
