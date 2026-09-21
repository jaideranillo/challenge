package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.SubscriptionRowMapper;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.domain.model.subscription.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link SubscriptionRepositoryPort}.
 *
 * <p>No {@code @Transactional}. No mutable state. Safe to share across virtual threads.
 *
 * <p>Every conditional write returns {@code boolean} from the affected-row count. Zero rows is
 * a normal, expected outcome (duplicate message, lost race) and must never throw.
 *
 * <p><strong>Interval binding (circuit operations).</strong> {@link Duration} parameters
 * ({@code baseCooldown}, {@code maxCooldown}) are bound as seconds ({@code long}) and combined
 * with {@code interval '1 second'} arithmetic inside the SQL. This keeps the binding type
 * simple (no PGInterval dependency) and lets Postgres do the multiplication natively.
 * Example: a 30-second base cooldown is bound as {@code 30L} and the SQL computes
 * {@code :base_secs::float8 * POWER(2.0, consecutive_opens) * interval '1 second'}.
 *
 * <p><strong>Pre-update exponent (ADR-006 Amendment B3).</strong> In a PostgreSQL UPDATE, the
 * right-hand side sees the pre-update row. A first trip has {@code consecutive_opens = 0}, so
 * {@code base * 2^0 = base}. Writing {@code consecutive_opens + 1} in the exponent silently
 * doubles every cooldown; the SQL below deliberately does not do that.
 */
@Repository
public class SubscriptionJdbcRepository implements SubscriptionRepositoryPort {

    private static final String SELECT_COLUMNS =
            "subscription_id, client_id, target_url, secret_ref, previous_secret_ref,"
                    + " previous_secret_expires_at, event_types, active, verification_state,"
                    + " max_concurrency, circuit_state, throttled_until";

    /**
     * SET clause shared by tripCircuit and reopenCircuit (ADR-006 Amendment B2).
     * A shared constant for the SQL text is acceptable; a shared method taking the guard
     * as a parameter is not — the guard is the contract, not an implementation detail.
     */
    private static final String OPEN_CIRCUIT_SET =
            " SET circuit_state = 'OPEN'::circuit_state,"
                    + "     circuit_opened_at = :now,"
                    + "     circuit_backoff = LEAST("
                    + "         :base_secs::float8 * POWER(2.0, consecutive_opens) * interval '1 second',"
                    + "         :max_secs::float8 * interval '1 second'"
                    + "     ),"
                    + "     consecutive_opens = consecutive_opens + 1,"
                    + "     updated_at = :now";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SubscriptionRowMapper rowMapper;

    public SubscriptionJdbcRepository(
            NamedParameterJdbcTemplate jdbcTemplate, SubscriptionRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
    }

    // -----------------------------------------------------------------------
    // TASK-004-17: reads
    // -----------------------------------------------------------------------

    /**
     * Returns active, verified subscriptions matching the event type for the given tenant.
     *
     * <p>{@code client_id} is a query predicate, not a post-hoc Java filter (ADR-002 §1.1 step 2).
     * There is no code path where a subscription belonging to a different client is materialized
     * in memory and then compared (A01).
     *
     * <p>{@code event_types} containment uses {@code @>} so the GIN index
     * ({@code idx_subscriptions_event_types}) is usable. {@code = ANY} or {@code LIKE} on
     * a client-supplied value would defeat the index.
     *
     * <p>{@code throttled_until} and {@code circuit_state} are <em>not</em> filtered here.
     * This is the ingest path, which writes {@code PENDING} rows; the relay's due-query
     * (ADR-002 §2.1) applies the circuit and throttle gates at scheduling time. Filtering here
     * would drop the event entirely rather than delaying it.
     *
     * <p>Returns empty list (never null) on no match (Effective Java Item 54).
     */
    @Override
    public List<Subscription> findActiveForEvent(String clientId, String eventType) {
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM subscriptions"
                + " WHERE client_id = :client_id"
                + "   AND event_types @> ARRAY[:event_type]::text[]"
                + "   AND active"
                + "   AND verification_state = 'VERIFIED'::verification_state";

        List<Subscription> result = jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("client_id", clientId)
                        .addValue("event_type", eventType),
                rowMapper);
        return result == null ? List.of() : result;
    }

    /**
     * Cross-tenant single-subscription read.
     *
     * <p>No {@code client_id} predicate, deliberately. The worker resolves the subscription for
     * a delivery it already holds and runs with no principal (ADR-002 §2.2 step 3). This is the
     * same carve-out ADR-007 §5.2 grants to {@code DeliveryPipelineRepositoryPort.findById} —
     * both are internal pipeline reads unreachable from a client-facing use case (ADR-007
     * Amendment E1).
     */
    @Override
    public Optional<Subscription> findById(UUID subscriptionId) {
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM subscriptions WHERE subscription_id = :id";

        List<Subscription> results = jdbcTemplate.query(
                sql, new MapSqlParameterSource("id", subscriptionId), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -----------------------------------------------------------------------
    // TASK-004-18: classification writes
    // -----------------------------------------------------------------------

    /**
     * Sets {@code active = false} for the subscription (ADR-004 §1 — 404/410 response).
     *
     * <p>Guarded on {@code active} so a repeat call is a no-op returning {@code false} rather
     * than a redundant write.
     *
     * <p>{@code verification_state} is <em>not</em> touched. ADR-005 §3 is explicit that
     * {@code active} and {@code verification_state} mean different things and are set by
     * different parties; deactivating must not erase the platform's verification proof.
     *
     * <p>No reactivation method: ADR-004 Q12 ships no reactivation path in v1.
     *
     * <p>{@code updated_at} comes from the database's {@code now()} — this method's signature
     * has no {@code Instant} parameter and is not changed here (logged in {@code docs/concerns.md}).
     */
    @Override
    public boolean deactivate(UUID subscriptionId) {
        String sql =
                "UPDATE subscriptions"
                        + " SET active = false, updated_at = now()"
                        + " WHERE subscription_id = :id AND active";

        return jdbcTemplate.update(sql, new MapSqlParameterSource("id", subscriptionId)) == 1;
    }

    /**
     * Monotonically advances the throttle window (ADR-004 §1 — 429 response).
     *
     * <p><strong>Deliberately unconditional on the current value.</strong> Two workers both
     * seeing a 429 should land the later {@code Retry-After}, not have one silently lose. There
     * is no {@code WHERE} guard on the prior value, unlike every other write in this class.
     *
     * <p><strong>Monotonicity decision:</strong> {@code GREATEST(throttled_until, :until)} is
     * used rather than a plain last-writer-wins assignment. An earlier {@code until} cannot
     * shorten a window another worker just extended; a later one still wins. This is the
     * deliberately chosen, safer reading (see TASK-004-18) — a plain assignment is the other
     * defensible option and is not used here.
     *
     * <p><strong>{@code updated_at} uses the database clock, not the bound {@code :until}.</strong>
     * {@code until} is the throttle deadline (a point in the future), not the time of this write.
     * Binding it to {@code updated_at} would let the audit column move backward: a later call
     * with an earlier {@code until} still updates the row (this write is unconditional) but would
     * set {@code updated_at} to a value earlier than a previous call's, corrupting any comparison
     * of {@code updated_at} against wall-clock time (e.g. the relay's staleness reclaim,
     * ADR-002 §2.1). {@code now()} is monotonic across calls; the bound value is not. Logged as a
     * third clock-source exception in {@code docs/concerns.md}.
     *
     * <p>{@code throttled_until} is not a circuit state. A 429 does not count toward the
     * breaker (ADR-004 §1). This method does not touch any circuit column.
     */
    @Override
    public boolean setThrottledUntil(UUID subscriptionId, Instant throttledUntil) {
        String sql =
                "UPDATE subscriptions"
                        + " SET throttled_until = GREATEST(throttled_until, :until),"
                        + "     updated_at = now()"
                        + " WHERE subscription_id = :id";

        OffsetDateTime until = OffsetDateTime.ofInstant(throttledUntil, ZoneOffset.UTC);
        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", subscriptionId)
                .addValue("until", until)) == 1;
    }

    // -----------------------------------------------------------------------
    // TASK-004-19: circuit operations
    // -----------------------------------------------------------------------

    /**
     * Opens the circuit breaker for a healthy destination that has started failing.
     *
     * <p><strong>Guard: {@code circuit_state = 'CLOSED'}.</strong>
     *
     * <p>The SET clause is identical to {@link #reopenCircuit} — both open the circuit and
     * escalate the cooldown — but the guards differ and the two are deliberately separate
     * methods (ADR-006 Amendment B2). A shared method taking the expected state as a parameter
     * would allow a caller to apply a trip precondition to a {@code HALF_OPEN} circuit,
     * double-counting a cooldown escalation. The {@code OPEN_CIRCUIT_SET} constant shares the
     * SQL text only; it does not share any precondition logic.
     *
     * <p><strong>Pre-update exponent (ADR-006 Amendment B3):</strong> the exponent reads
     * {@code consecutive_opens} before the row is updated. Do not add {@code + 1} to the
     * exponent — that would silently double every cooldown.
     *
     * <p><strong>Interval binding:</strong> {@code baseCooldown} and {@code maxCooldown} are
     * bound as seconds ({@code long}) and the SQL multiplies them by {@code interval '1 second'}.
     * This avoids a PGInterval dependency and keeps the arithmetic inside the atomic UPDATE.
     */
    @Override
    public boolean tripCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now) {
        String sql = "UPDATE subscriptions" + OPEN_CIRCUIT_SET
                + " WHERE subscription_id = :id"
                + "   AND circuit_state = 'CLOSED'::circuit_state";

        return jdbcTemplate.update(sql, circuitParams(subscriptionId, baseCooldown, maxCooldown, now)) == 1;
    }

    /**
     * Re-opens the circuit breaker after a failed probe against a recovering destination.
     *
     * <p><strong>Guard: {@code circuit_state = 'HALF_OPEN'}.</strong> A failed probe depends on
     * this guard: applying it to a {@code CLOSED} circuit would trip a destination nothing is
     * currently failing against.
     *
     * <p>Identical SET clause to {@link #tripCircuit}; different guard. See the class-level
     * javadoc and {@link #tripCircuit} for the full explanation of why these are separate methods.
     */
    @Override
    public boolean reopenCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now) {
        String sql = "UPDATE subscriptions" + OPEN_CIRCUIT_SET
                + " WHERE subscription_id = :id"
                + "   AND circuit_state = 'HALF_OPEN'::circuit_state";

        return jdbcTemplate.update(sql, circuitParams(subscriptionId, baseCooldown, maxCooldown, now)) == 1;
    }

    /**
     * Promotes the circuit to {@code HALF_OPEN} after the cooldown has elapsed.
     *
     * <p><strong>Guard: {@code circuit_state = 'OPEN' AND circuit_opened_at < :as_of - circuit_backoff}.</strong>
     * The compound guard ensures the row is only promoted if the cooldown interval has actually
     * passed. The elapsed-cooldown check happens <em>inside</em> the atomic UPDATE — never as a
     * preceding read — so the check and the transition are serialized by row-level locking.
     */
    @Override
    public boolean promoteToHalfOpen(UUID subscriptionId, Instant asOf) {
        String sql =
                "UPDATE subscriptions"
                        + " SET circuit_state = 'HALF_OPEN'::circuit_state"
                        + " WHERE subscription_id = :id"
                        + "   AND circuit_state = 'OPEN'::circuit_state"
                        + "   AND circuit_opened_at < :as_of - circuit_backoff";

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", subscriptionId)
                .addValue("as_of", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))) == 1;
    }

    /**
     * Closes the circuit breaker after a successful probe.
     *
     * <p><strong>Guard: {@code circuit_state = 'HALF_OPEN'}.</strong> Only a probe may close a
     * circuit. Resets all four circuit columns to their initial values; a client that recovers
     * must fully forget the escalation or it stays on a long cooldown forever.
     */
    @Override
    public boolean closeCircuit(UUID subscriptionId, Instant now) {
        String sql =
                "UPDATE subscriptions"
                        + " SET circuit_state = 'CLOSED'::circuit_state,"
                        + "     circuit_opened_at = NULL,"
                        + "     circuit_backoff = NULL,"
                        + "     consecutive_opens = 0,"
                        + "     updated_at = :now"
                        + " WHERE subscription_id = :id"
                        + "   AND circuit_state = 'HALF_OPEN'::circuit_state";

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", subscriptionId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))) == 1;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private MapSqlParameterSource circuitParams(
            UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now) {
        return new MapSqlParameterSource()
                .addValue("id", subscriptionId)
                .addValue("base_secs", baseCooldown.toSeconds())
                .addValue("max_secs", maxCooldown.toSeconds())
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
