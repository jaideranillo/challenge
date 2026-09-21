package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Duration;
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

/**
 * Integration tests for {@link SubscriptionJdbcRepository}'s four circuit operations:
 * {@code tripCircuit}, {@code reopenCircuit}, {@code promoteToHalfOpen}, {@code closeCircuit}
 * (TASK-004-19, ADR-006 Amendments B1-B3).
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC —
 * {@code interval} arithmetic, {@code POWER}, {@code LEAST} and native enums are Postgres
 * behaviors that a mock would not exercise.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubscriptionCircuitOpsTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofSeconds(600);

    @Autowired
    SubscriptionJdbcRepository repo;

    @Autowired
    DeliveryPipelineJdbcRepository pipelineRepo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private String clientId;
    private TransactionTemplate tx;

    @BeforeEach
    void fixtures() {
        clientId = "client-circuit-" + UUID.randomUUID();
        tx = new TransactionTemplate(txManager);
    }

    // -----------------------------------------------------------------------
    // tripCircuit
    // -----------------------------------------------------------------------

    @Test
    void tripCircuit_fromClosed_opensSetsBackoffAndIncrementsConsecutiveOpens() {
        UUID sid = insertSubscription("CLOSED");
        OffsetDateTime updatedBefore = readUpdatedAt(sid);
        Instant now = Instant.now();

        boolean result = repo.tripCircuit(sid, BASE, MAX, now);

        assertThat(result).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readCircuitOpenedAt(sid).toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(now.truncatedTo(ChronoUnit.SECONDS));
        assertThat(readBackoffSeconds(sid)).isCloseTo(30.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(1);
        assertThat(readUpdatedAt(sid)).isAfterOrEqualTo(updatedBefore);
    }

    @Test
    void tripCircuit_fromOpen_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("OPEN", 1, Instant.now().minusSeconds(10), 30.0);
        Integer before = readConsecutiveOpens(sid);

        boolean result = repo.tripCircuit(sid, BASE, MAX, Instant.now());

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(before);
    }

    /** Property 1: tripCircuit from HALF_OPEN returns false and changes nothing (the guard distinction). */
    @Test
    void tripCircuit_fromHalfOpen_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("HALF_OPEN", 1, null, null);
        Integer before = readConsecutiveOpens(sid);

        boolean result = repo.tripCircuit(sid, BASE, MAX, Instant.now());

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("HALF_OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(before);
    }

    @Test
    void tripCircuit_unknownId_returnsFalseWithoutThrowing() {
        assertThat(repo.tripCircuit(UUID.randomUUID(), BASE, MAX, Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // reopenCircuit
    // -----------------------------------------------------------------------

    /** Property 3: a failed probe from HALF_OPEN escalates the cooldown. */
    @Test
    void reopenCircuit_fromHalfOpen_opensAndEscalatesCooldown() {
        Instant now = Instant.now();
        // Simulates a subscription that already tripped once (consecutive_opens = 1, backoff = base)
        // and was promoted to HALF_OPEN for a probe.
        UUID sid = insertSubscription("HALF_OPEN", 1, null, 30.0);

        boolean result = repo.reopenCircuit(sid, BASE, MAX, now);

        assertThat(result).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readCircuitOpenedAt(sid).toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(now.truncatedTo(ChronoUnit.SECONDS));
        assertThat(readBackoffSeconds(sid)).isCloseTo(60.0, within(0.01)); // base * 2^1
        assertThat(readConsecutiveOpens(sid)).isEqualTo(2);
    }

    /** Property 2: reopenCircuit from CLOSED returns false and changes nothing (the other half). */
    @Test
    void reopenCircuit_fromClosed_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("CLOSED");
        Integer before = readConsecutiveOpens(sid);

        boolean result = repo.reopenCircuit(sid, BASE, MAX, Instant.now());

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("CLOSED");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(before);
    }

    @Test
    void reopenCircuit_fromOpen_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("OPEN", 1, Instant.now().minusSeconds(10), 30.0);
        Integer before = readConsecutiveOpens(sid);

        boolean result = repo.reopenCircuit(sid, BASE, MAX, Instant.now());

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(before);
    }

    @Test
    void reopenCircuit_unknownId_returnsFalseWithoutThrowing() {
        assertThat(repo.reopenCircuit(UUID.randomUUID(), BASE, MAX, Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // promoteToHalfOpen
    // -----------------------------------------------------------------------

    @Test
    void promoteToHalfOpen_cooldownElapsed_returnsTrueAndSetsHalfOpen() {
        Instant openedAt = Instant.now().minusSeconds(120);
        UUID sid = insertSubscription("OPEN", 1, openedAt, 30.0); // opened 120s ago, backoff 30s: elapsed
        Instant asOf = Instant.now();

        boolean result = repo.promoteToHalfOpen(sid, asOf);

        assertThat(result).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("HALF_OPEN");
    }

    @Test
    void promoteToHalfOpen_cooldownNotElapsed_returnsFalseAndRemainsOpen() {
        Instant openedAt = Instant.now().minusSeconds(10);
        UUID sid = insertSubscription("OPEN", 1, openedAt, 30.0); // opened 10s ago, backoff 30s: not elapsed
        Instant asOf = Instant.now();

        boolean result = repo.promoteToHalfOpen(sid, asOf);

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
    }

    @Test
    void promoteToHalfOpen_fromClosed_returnsFalse() {
        UUID sid = insertSubscription("CLOSED");
        assertThat(repo.promoteToHalfOpen(sid, Instant.now())).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("CLOSED");
    }

    @Test
    void promoteToHalfOpen_fromHalfOpen_returnsFalse() {
        UUID sid = insertSubscription("HALF_OPEN", 1, null, null);
        assertThat(repo.promoteToHalfOpen(sid, Instant.now())).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("HALF_OPEN");
    }

    @Test
    void promoteToHalfOpen_unknownId_returnsFalseWithoutThrowing() {
        assertThat(repo.promoteToHalfOpen(UUID.randomUUID(), Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // closeCircuit
    // -----------------------------------------------------------------------

    /** Property 9: closeCircuit resets all four circuit columns. */
    @Test
    void closeCircuit_fromHalfOpen_resetsAllFourColumns() {
        UUID sid = insertSubscription("HALF_OPEN", 3, Instant.now().minusSeconds(60), 30.0);
        Instant now = Instant.now();

        boolean result = repo.closeCircuit(sid, now);

        assertThat(result).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("CLOSED");
        assertThat(readCircuitOpenedAt(sid)).isNull();
        assertThat(readBackoffSeconds(sid)).isNull();
        assertThat(readConsecutiveOpens(sid)).isZero();
    }

    /** Property 10: only a probe may close a circuit. */
    @Test
    void closeCircuit_fromOpen_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("OPEN", 2, Instant.now().minusSeconds(10), 30.0);
        Integer before = readConsecutiveOpens(sid);

        boolean result = repo.closeCircuit(sid, Instant.now());

        assertThat(result).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(before);
    }

    @Test
    void closeCircuit_fromClosed_returnsFalseAndChangesNothing() {
        UUID sid = insertSubscription("CLOSED");
        assertThat(repo.closeCircuit(sid, Instant.now())).isFalse();
        assertThat(readCircuitState(sid)).isEqualTo("CLOSED");
    }

    @Test
    void closeCircuit_unknownId_returnsFalseWithoutThrowing() {
        assertThat(repo.closeCircuit(UUID.randomUUID(), Instant.now())).isFalse();
    }

    // -----------------------------------------------------------------------
    // Property 4: first-writer-wins
    // -----------------------------------------------------------------------

    @Test
    void firstWriterWins_secondTripCircuitCallOnSameRowFails() {
        Instant now = Instant.now();
        UUID sid = insertSubscription("CLOSED");

        boolean first = repo.tripCircuit(sid, BASE, MAX, now);
        boolean second = repo.tripCircuit(sid, BASE, MAX, now.plusSeconds(1));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(readConsecutiveOpens(sid)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Property 5: the cooldown ladder (catches the pre-update-exponent off-by-one)
    // -----------------------------------------------------------------------

    @Test
    void cooldownLadder_firstTripEqualsBase_thenDoublesEachEscalation() {
        Duration base = Duration.ofSeconds(30);
        Duration max = Duration.ofSeconds(10_000); // large enough never to cap in this test
        Instant t0 = Instant.now();
        UUID sid = insertSubscription("CLOSED");

        // First trip: consecutive_opens is 0 pre-update, so base * 2^0 = base.
        // This is the assertion that catches a "+ 1" bug in the exponent.
        assertThat(repo.tripCircuit(sid, base, max, t0)).isTrue();
        assertThat(readBackoffSeconds(sid)).isCloseTo(30.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(1);

        // Fixture flip to HALF_OPEN: isolates the backoff computation of reopenCircuit from
        // the promotion guard, which is covered separately by the promoteToHalfOpen tests.
        flipCircuitState(sid, "HALF_OPEN");

        // Second escalation: consecutive_opens is 1 pre-update, so base * 2^1 = 2 * base.
        assertThat(repo.reopenCircuit(sid, base, max, t0.plusSeconds(1))).isTrue();
        assertThat(readBackoffSeconds(sid)).isCloseTo(60.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(2);

        flipCircuitState(sid, "HALF_OPEN");

        // Third escalation: consecutive_opens is 2 pre-update, so base * 2^2 = 4 * base.
        assertThat(repo.reopenCircuit(sid, base, max, t0.plusSeconds(2))).isTrue();
        assertThat(readBackoffSeconds(sid)).isCloseTo(120.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Property 6: the cap
    // -----------------------------------------------------------------------

    @Test
    void cooldownCap_exceedsMax_isCapped() {
        Duration base = Duration.ofSeconds(30);
        Duration max = Duration.ofSeconds(600);
        // consecutive_opens = 10 pre-update: base * 2^10 = 30 * 1024 = 30720s, far above max.
        UUID sid = insertSubscription("CLOSED", 10, null, null);

        assertThat(repo.tripCircuit(sid, base, max, Instant.now())).isTrue();

        assertThat(readBackoffSeconds(sid)).isCloseTo(600.0, within(0.01));
    }

    // -----------------------------------------------------------------------
    // Property 11 + 12: full lifecycle, and the untouched columns
    // -----------------------------------------------------------------------

    /**
     * CLOSED -> trip -> OPEN -> promote -> HALF_OPEN -> reopen -> OPEN -> promote -> HALF_OPEN
     * -> close -> CLOSED, asserting state and cooldown after every step and that
     * {@code consecutive_opens} is 0 at the end (property 11). Also asserts {@code active},
     * {@code verification_state} and {@code throttled_until} are untouched by any of the four
     * operations throughout (property 12; ADR-004 §1 — a throttle and a breaker trip are
     * different mechanisms).
     */
    @Test
    void fullLifecycle_closedToOpenToHalfOpenToOpenToHalfOpenToClosed() {
        Instant throttledUntil = Instant.now().plusSeconds(3600);
        UUID sid = insertSubscriptionWithThrottle(throttledUntil);

        // CLOSED -> trip -> OPEN (backoff = base, consecutive_opens = 1)
        Instant t0 = Instant.now();
        assertThat(repo.tripCircuit(sid, BASE, MAX, t0)).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readBackoffSeconds(sid)).isCloseTo(30.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(1);
        assertUntouched(sid, throttledUntil);

        // OPEN -> promote -> HALF_OPEN (cooldown of 30s elapsed by t0 + 35s)
        Instant t1 = t0.plusSeconds(35);
        assertThat(repo.promoteToHalfOpen(sid, t1)).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("HALF_OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(1);
        assertUntouched(sid, throttledUntil);

        // HALF_OPEN -> reopen (failed probe) -> OPEN (backoff = 2 * base, consecutive_opens = 2)
        Instant t2 = t1.plusSeconds(1);
        assertThat(repo.reopenCircuit(sid, BASE, MAX, t2)).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("OPEN");
        assertThat(readBackoffSeconds(sid)).isCloseTo(60.0, within(0.01));
        assertThat(readConsecutiveOpens(sid)).isEqualTo(2);
        assertUntouched(sid, throttledUntil);

        // OPEN -> promote -> HALF_OPEN (cooldown of 60s elapsed by t2 + 65s)
        Instant t3 = t2.plusSeconds(65);
        assertThat(repo.promoteToHalfOpen(sid, t3)).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("HALF_OPEN");
        assertThat(readConsecutiveOpens(sid)).isEqualTo(2);
        assertUntouched(sid, throttledUntil);

        // HALF_OPEN -> close (successful probe) -> CLOSED, all four columns reset
        Instant t4 = t3.plusSeconds(1);
        assertThat(repo.closeCircuit(sid, t4)).isTrue();
        assertThat(readCircuitState(sid)).isEqualTo("CLOSED");
        assertThat(readCircuitOpenedAt(sid)).isNull();
        assertThat(readBackoffSeconds(sid)).isNull();
        assertThat(readConsecutiveOpens(sid)).isZero();
        assertUntouched(sid, throttledUntil);
    }

    // -----------------------------------------------------------------------
    // Property 13: the claimDue seam
    // -----------------------------------------------------------------------

    /**
     * Proves the ADR-002 §2.1 / ADR-006 §1.2 seam: after {@code promoteToHalfOpen}, the
     * subscription's due deliveries are admitted by {@code claimDue}. {@code claimDue} itself
     * (TASK-004-11) is out of scope; this only exercises the boundary.
     */
    @Test
    void promoteToHalfOpen_thenClaimDue_admitsSubscriptionsDeliveries() {
        Instant t0 = Instant.now();
        UUID sid = insertSubscription("CLOSED");
        assertThat(repo.tripCircuit(sid, BASE, MAX, t0)).isTrue(); // OPEN, backoff = 30s, opened_at = t0

        UUID deliveryId = seedDueDelivery(sid, t0.minus(1, ChronoUnit.MINUTES));

        // A large batch limit: claimDue has no client_id predicate (ADR-002 §2.1 is a
        // cross-tenant relay query) and this table is shared with every other integration
        // test in the suite. A small LIMIT combined with ORDER BY next_attempt_at could rank
        // this fixture's row behind unrelated rows seeded elsewhere and hide the seam being
        // tested; a large limit makes the assertion depend only on the predicate, not on
        // suite-wide row volume or ordering.
        int limit = 10_000;

        Instant beforeCooldown = t0.plusSeconds(5);
        assertThat(claimDue(limit, beforeCooldown).stream().map(Delivery::deliveryId))
                .as("circuit still OPEN and cooldown not elapsed")
                .doesNotContain(deliveryId);

        Instant afterCooldown = t0.plusSeconds(35);
        assertThat(repo.promoteToHalfOpen(sid, afterCooldown)).isTrue();

        assertThat(claimDue(limit, afterCooldown).stream().map(Delivery::deliveryId))
                .as("HALF_OPEN admits the probe")
                .contains(deliveryId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void assertUntouched(UUID id, Instant expectedThrottledUntil) {
        assertThat(readActive(id)).isTrue();
        assertThat(readVerificationState(id)).isEqualTo("VERIFIED");
        assertThat(readThrottledUntil(id).toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(expectedThrottledUntil.truncatedTo(ChronoUnit.SECONDS));
    }

    private List<Delivery> claimDue(int limit, Instant asOf) {
        @SuppressWarnings("unchecked")
        List<Delivery>[] result = new List[1];
        tx.executeWithoutResult(status -> result[0] = pipelineRepo.claimDue(limit, asOf));
        return result[0];
    }

    private UUID seedDueDelivery(UUID subscriptionId, Instant createdAt) {
        String eventId = "EVT-CIRCUIT-" + UUID.randomUUID();
        OffsetDateTime ca = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', :ca)",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId).addValue("ca", ca));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status, "
                        + "next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, 'PENDING'::delivery_status, :ca, :ca, :ca, :ca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("eid", eventId)
                        .addValue("sid", subscriptionId)
                        .addValue("cid", clientId)
                        .addValue("ca", ca));
        return deliveryId;
    }

    private void flipCircuitState(UUID id, String circuitState) {
        jdbc.update(
                "UPDATE subscriptions SET circuit_state = :cs::circuit_state WHERE subscription_id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("cs", circuitState));
    }

    /** Default fixture: no throttle, no prior escalation. */
    private UUID insertSubscription(String circuitState) {
        return insertSubscription(circuitState, 0, null, null);
    }

    private UUID insertSubscription(
            String circuitState, int consecutiveOpens, Instant circuitOpenedAt, Double circuitBackoffSeconds) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types,"
                        + " active, verification_state, circuit_state, consecutive_opens, circuit_opened_at,"
                        + " circuit_backoff)"
                        + " VALUES (:id, :cid, 'https://example.com/hook', 'ref',"
                        + " ARRAY['payment.completed']::text[], true, 'VERIFIED'::verification_state,"
                        + " :cs::circuit_state, :co, :oa,"
                        + " CASE WHEN :cb::float8 IS NULL THEN NULL ELSE :cb::float8 * interval '1 second' END)",
                new MapSqlParameterSource()
                        .addValue("id", sid)
                        .addValue("cid", clientId)
                        .addValue("cs", circuitState)
                        .addValue("co", consecutiveOpens)
                        .addValue("oa", circuitOpenedAt == null
                                ? null
                                : OffsetDateTime.ofInstant(circuitOpenedAt, ZoneOffset.UTC))
                        .addValue("cb", circuitBackoffSeconds));
        return sid;
    }

    private UUID insertSubscriptionWithThrottle(Instant throttledUntil) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types,"
                        + " active, verification_state, throttled_until, circuit_state)"
                        + " VALUES (:id, :cid, 'https://example.com/hook', 'ref',"
                        + " ARRAY['payment.completed']::text[], true, 'VERIFIED'::verification_state, :tu,"
                        + " 'CLOSED'::circuit_state)",
                new MapSqlParameterSource()
                        .addValue("id", sid)
                        .addValue("cid", clientId)
                        .addValue("tu", OffsetDateTime.ofInstant(throttledUntil, ZoneOffset.UTC)));
        return sid;
    }

    private String readCircuitState(UUID id) {
        return jdbc.queryForObject(
                "SELECT circuit_state::text FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private Integer readConsecutiveOpens(UUID id) {
        return jdbc.queryForObject(
                "SELECT consecutive_opens FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
    }

    private OffsetDateTime readCircuitOpenedAt(UUID id) {
        return jdbc.queryForObject(
                "SELECT circuit_opened_at FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getObject("circuit_opened_at", OffsetDateTime.class));
    }

    private Double readBackoffSeconds(UUID id) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM circuit_backoff) FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), Double.class);
    }

    private OffsetDateTime readUpdatedAt(UUID id) {
        return jdbc.queryForObject(
                "SELECT updated_at FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Boolean readActive(UUID id) {
        return jdbc.queryForObject(
                "SELECT active FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), Boolean.class);
    }

    private String readVerificationState(UUID id) {
        return jdbc.queryForObject(
                "SELECT verification_state::text FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private OffsetDateTime readThrottledUntil(UUID id) {
        return jdbc.queryForObject(
                "SELECT throttled_until FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getObject("throttled_until", OffsetDateTime.class));
    }
}
