package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.DeliveryRowMapper;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JDBC implementation of {@link DeliveryPipelineRepositoryPort}.
 *
 * <p>No {@code @Transactional}: transaction boundaries are the use case's responsibility
 * (CLAUDE.md, ADR-005 §1). Each method is a single, atomic SQL statement; the caller's
 * transaction context is the unit of work.
 *
 * <p>No {@code synchronized} and no {@code ThreadLocal} — this bean is stateless and safe
 * to share across virtual threads (ADR-002 §2).
 */
@Repository
public class DeliveryPipelineJdbcRepository implements DeliveryPipelineRepositoryPort {

    // Columns selected in every delivery read — explicit list prevents SELECT * collisions
    // when this query is used inside a JOIN (e.g. claimDue).
    private static final String DELIVERY_COLUMNS =
            "d.delivery_id, d.event_id, d.subscription_id, d.client_id, d.status, d.origin, "
                    + "d.replayed_from, d.attempt_count, d.next_attempt_at, d.last_error, "
                    + "d.delivered_at, d.event_created_at, d.trace_context";

    // The relay's 5-minute recovery push. This is a dispatch policy rather than a storage
    // detail; it lives here as a constant because claimDue()'s port has no interval parameter
    // (logged in docs/concerns.md).
    private static final String CLAIM_DUE_PUSH_INTERVAL = "5 minutes";

    // Shared by insert, insertIfAbsent and their RETURNING clauses, and by
    // findLiveByEventAndSubscription's SELECT list, so the three statements cannot drift
    // apart on which columns a delivery carries.
    private static final String INSERT_COLUMNS =
            "delivery_id, event_id, subscription_id, client_id, status, origin,"
                    + " replayed_from, attempt_count, next_attempt_at, last_error, delivered_at,"
                    + " event_created_at, trace_context";

    private static final String INSERT_VALUES =
            ":delivery_id, :event_id, :subscription_id, :client_id,"
                    + " :status::delivery_status, :origin::delivery_origin,"
                    + " :replayed_from, :attempt_count, :next_attempt_at, :last_error, :delivered_at,"
                    + " :event_created_at, :trace_context";

    // idx_deliveries_live_pair (V2): status NOT IN ('DELIVERED', 'DEAD', 'FAILED'). Repeated
    // verbatim here (as the ON CONFLICT inference predicate) and in
    // findLiveByEventAndSubscription's WHERE clause. If that index's predicate ever changes,
    // both must change with it, or a conflict could report a live pair that the read below
    // would fail to find, or vice versa.
    private static final String LIVE_STATUS_PREDICATE =
            "status NOT IN ('DELIVERED'::delivery_status, 'DEAD'::delivery_status, 'FAILED'::delivery_status)";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DeliveryRowMapper deliveryRowMapper;

    public DeliveryPipelineJdbcRepository(
            NamedParameterJdbcTemplate jdbcTemplate, DeliveryRowMapper deliveryRowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliveryRowMapper = deliveryRowMapper;
    }

    // -----------------------------------------------------------------------
    // TASK-004-06: insert and findById
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Binds {@code event_created_at} from {@link Delivery#eventCreatedAt()} — never from
     * {@code now()} or {@code created_at}. For a {@code REPLAY} or {@code RECOVERED} row the two
     * differ; binding from the aggregate is the whole point of ADR-003 Amendment A4.
     *
     * <p>Does not catch {@link org.springframework.dao.DuplicateKeyException}. The partial unique
     * index {@code idx_deliveries_live_pair} is the idempotency guard (ADR-003 §2) and its
     * violation is a real outcome the use case must see (ADR-005 §1 maps it to 409). Swallowing
     * it here would make a double replay look like a success.
     */
    @Override
    public Delivery insert(Delivery delivery) {
        String sql = "INSERT INTO deliveries (" + INSERT_COLUMNS + ") VALUES (" + INSERT_VALUES + ")"
                + " RETURNING " + INSERT_COLUMNS;

        return jdbcTemplate.queryForObject(sql, insertParams(delivery), insertRowMapper());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>ON CONFLICT inference clause coupling.</strong> The {@code WHERE} predicate
     * below repeats {@code idx_deliveries_live_pair} (V2) verbatim, in the same order and with
     * the same literals as the index. Postgres cannot infer a partial unique index for
     * {@code ON CONFLICT} without an identical predicate here; a mismatch fails at runtime
     * with "there is no unique or exclusion constraint matching the ON CONFLICT specification",
     * not at compile time. If the index's predicate ever changes, this statement changes with
     * it — see {@link #LIVE_STATUS_PREDICATE}.
     *
     * <p>Shares its column list, bindings and {@code RETURNING} mapping with {@link #insert}
     * through {@link #insertParams(Delivery)} and {@link #INSERT_COLUMNS}, so ingest and replay
     * rows can never carry different columns.
     */
    @Override
    public Optional<Delivery> insertIfAbsent(Delivery delivery) {
        String sql = "INSERT INTO deliveries (" + INSERT_COLUMNS + ") VALUES (" + INSERT_VALUES + ")"
                + " ON CONFLICT (event_id, subscription_id) WHERE " + LIVE_STATUS_PREDICATE
                + " DO NOTHING"
                + " RETURNING " + INSERT_COLUMNS;

        List<Delivery> result = jdbcTemplate.query(sql, insertParams(delivery), insertRowMapper());
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    private MapSqlParameterSource insertParams(Delivery delivery) {
        Map<String, Object> params = new HashMap<>();
        params.put("delivery_id", delivery.deliveryId());
        params.put("event_id", delivery.eventId());
        params.put("subscription_id", delivery.subscriptionId());
        params.put("client_id", delivery.clientId());
        params.put("status", delivery.status().name());
        params.put("origin", delivery.origin().name());
        params.put("replayed_from", delivery.replayedFrom().orElse(null));
        params.put("attempt_count", delivery.attemptCount());
        params.put("next_attempt_at", delivery.nextAttemptAt()
                .map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC)).orElse(null));
        params.put("last_error", delivery.lastError().orElse(null));
        params.put("delivered_at", delivery.deliveredAt()
                .map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC)).orElse(null));
        // event_created_at: bound from the aggregate, never from now() (ADR-003 Amendment A4)
        params.put("event_created_at", OffsetDateTime.ofInstant(delivery.eventCreatedAt(), ZoneOffset.UTC));
        params.put("trace_context", delivery.traceContext().orElse(null));
        return new MapSqlParameterSource(params);
    }

    /**
     * {@inheritDoc}
     *
     * <p>No {@code client_id} predicate, deliberately. This is the worker's load of the row it
     * just claimed (ADR-002 §2.2 steps 5-6) and it runs with no principal. The unscoped read is
     * safe because this method lives on {@link DeliveryPipelineRepositoryPort}, which no
     * client-facing use case injects; the tenant-scoped read lives on
     * {@link com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort}
     * and is the one a client-facing use case holds (ADR-007 Amendment E1).
     */
    @Override
    public Optional<Delivery> findById(UUID deliveryId) {
        String sql =
                "SELECT delivery_id, event_id, subscription_id, client_id, status, origin,"
                        + " replayed_from, attempt_count, next_attempt_at, last_error, delivered_at,"
                        + " event_created_at, trace_context"
                        + " FROM deliveries WHERE delivery_id = :id";

        List<Delivery> results = jdbcTemplate.query(
                sql, new MapSqlParameterSource("id", deliveryId), insertRowMapper());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses the identical {@link #LIVE_STATUS_PREDICATE} that {@link #insertIfAbsent} uses
     * for its {@code ON CONFLICT} inference clause, so a conflict there can never fail to find
     * a row here. The unique partial index guarantees at most one match; more than one row is
     * a broken invariant and fails loudly rather than picking one silently.
     */
    @Override
    public Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId) {
        String sql = "SELECT " + INSERT_COLUMNS + " FROM deliveries"
                + " WHERE event_id = :event_id AND subscription_id = :subscription_id"
                + "   AND " + LIVE_STATUS_PREDICATE;

        List<Delivery> results = jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("subscription_id", subscriptionId), insertRowMapper());

        if (results.size() > 1) {
            throw new IllegalStateException(
                    "idx_deliveries_live_pair invariant broken: more than one live delivery for"
                            + " event_id/subscription_id pair");
        }
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -----------------------------------------------------------------------
    // TASK-004-07: claimForProcessing (correctness-critical)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p><strong>ADR-002 §2.2 step 1.</strong> This is the single mechanism preventing a double
     * POST (ADR-003 Q3 resolution). The guard {@code status = 'QUEUED'} is not a convenience
     * check; the {@code QUEUED} precondition <em>is</em> the operation.
     *
     * <p>Zero rows affected returns {@code false} — not an exception, not an error log. It is the
     * <em>designed</em> outcome for a redelivered SQS message (ADR-002 §2.2 step 2 follows with
     * {@code DeleteMessage}). Throwing here would turn a benign duplicate into a crash loop at
     * {@code maxReceiveCount = 3} and push a healthy delivery to the DLQ (A10).
     */
    @Override
    public boolean claimForProcessing(UUID deliveryId, Instant now) {
        String sql =
                "UPDATE deliveries"
                        + " SET status = 'PROCESSING'::delivery_status,"
                        + "     updated_at = :now"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status = 'QUEUED'::delivery_status";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        return rows == 1;
    }

    // -----------------------------------------------------------------------
    // TASK-004-09: four outcome writes
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Guard: {@code status = 'PROCESSING'}. Uses {@code deliveredAt} for both
     * {@code delivered_at} and {@code updated_at} — one instant, one attempt outcome.
     * {@code event_created_at} is not touched (ADR-003 Amendment A4's immutability rule).
     */
    @Override
    public boolean markDelivered(UUID deliveryId, Instant deliveredAt) {
        String sql =
                "UPDATE deliveries"
                        + " SET status = 'DELIVERED'::delivery_status,"
                        + "     delivered_at = :delivered_at,"
                        + "     next_attempt_at = NULL,"
                        + "     updated_at = :delivered_at"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status = 'PROCESSING'::delivery_status";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("delivered_at", OffsetDateTime.ofInstant(deliveredAt, ZoneOffset.UTC)));
        return rows == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Guard: {@code status = 'PROCESSING'}. {@code attempt_count} increments in SQL
     * ({@code attempt_count + 1}), never from a value the caller read first — two writers
     * could otherwise land the same count.
     * {@code event_created_at} is not touched (ADR-003 Amendment A4's immutability rule).
     */
    @Override
    public boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now) {
        String sql =
                "UPDATE deliveries"
                        + " SET status = 'RETRYING'::delivery_status,"
                        + "     attempt_count = attempt_count + 1,"
                        + "     next_attempt_at = :next_attempt_at,"
                        + "     last_error = :last_error,"
                        + "     updated_at = :now"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status = 'PROCESSING'::delivery_status";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("next_attempt_at", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                .addValue("last_error", lastError)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        return rows == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Guard: {@code status = 'PROCESSING'}. {@code next_attempt_at = NULL} is required:
     * a terminal row with a non-null {@code next_attempt_at} would remain visible to the
     * due-query's partial index and leak into every relay poll.
     * {@code event_created_at} is not touched (ADR-003 Amendment A4's immutability rule).
     */
    @Override
    public boolean markDead(UUID deliveryId, String lastError, Instant now) {
        String sql =
                "UPDATE deliveries"
                        + " SET status = 'DEAD'::delivery_status,"
                        + "     next_attempt_at = NULL,"
                        + "     last_error = :last_error,"
                        + "     updated_at = :now"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status = 'PROCESSING'::delivery_status";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("last_error", lastError)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        return rows == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Guard: {@code status IN ('QUEUED', 'PROCESSING')}. A DLQ message arrives without
     * knowing which prior state the row is in (ADR-003 §1's machine admits both
     * {@code QUEUED -> FAILED} and {@code PROCESSING -> FAILED}). Terminal states
     * ({@code DELIVERED}, {@code DEAD}, {@code FAILED}) are excluded so a late DLQ message
     * cannot overwrite a row that already reached a terminal state.
     * {@code event_created_at} is not touched (ADR-003 Amendment A4's immutability rule).
     */
    @Override
    public boolean markFailed(UUID deliveryId, String lastError, Instant now) {
        String sql =
                "UPDATE deliveries"
                        + " SET status = 'FAILED'::delivery_status,"
                        + "     last_error = :last_error,"
                        + "     updated_at = :now"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status IN ('QUEUED'::delivery_status, 'PROCESSING'::delivery_status)";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("last_error", lastError)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        return rows == 1;
    }

    // -----------------------------------------------------------------------
    // TASK-004-10: deferDelivery
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p><strong>ADR-002 §2.2 steps 3-4.</strong> No attempt occurred.
     * <ul>
     *   <li><strong>Must not</strong> increment {@code attempt_count} — a deferral that consumed
     *       retry budget would burn ADR-004 §1's six-step schedule on attempts that never happened.
     *   <li><strong>Must not</strong> write {@code last_error} — there was no error; there was no
     *       request.
     *   <li><strong>Must not</strong> insert a {@code delivery_attempts} row — ADR-003 §3's attempt
     *       history must contain attempts, and a deferral would pollute the audit trail.
     * </ul>
     *
     * <p>{@code updated_at} comes from the database's {@code now()} because this method's arity
     * is fixed at two parameters (no caller {@code Instant}). This is a deliberate inconsistency
     * with the rest of the port, logged in {@code docs/concerns.md}.
     */
    @Override
    public boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt) {
        String sql =
                "UPDATE deliveries"
                        + " SET next_attempt_at = :next_attempt_at,"
                        + "     updated_at = now()"
                        + " WHERE delivery_id = :delivery_id"
                        + "   AND status = 'QUEUED'::delivery_status";

        int rows = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("next_attempt_at", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC)));
        return rows == 1;
    }

    // -----------------------------------------------------------------------
    // TASK-004-11: claimDue (correctness-critical)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Requires an active transaction (asserted, throws {@link IllegalStateException} otherwise) —
     * {@code SKIP LOCKED} outside one lets every relay instance claim the same batch.
     *
     * <p>{@code FOR UPDATE OF d} locks only {@code deliveries}, not {@code subscriptions} — locking
     * the driving row would make relay instances skip each other's rows on a busy subscription.
     *
     * <p>Cap is per-subscription {@code LATERAL} (bounded by {@code remaining_allowance}) before
     * the outer {@code LIMIT :batch_limit} — capping a global pool after selection would let one
     * saturated subscription starve the rest. {@code CROSS JOIN LATERAL}, not {@code LEFT}: Postgres
     * rejects a locking clause on the nullable side of an outer join.
     */
    @Override
    public List<Delivery> claimDue(int batchLimit, Instant asOf) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "claimDue must be called within an active transaction. "
                            + "SKIP LOCKED outside a transaction takes no lasting locks and "
                            + "every caller would claim the same batch (A10 fail-closed).");
        }
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("batchLimit must be positive, was " + batchLimit);
        }

        // One compound statement: SELECT (LATERAL-bounded, SKIP LOCKED) inside a CTE, UPDATE in
        // a second CTE, RETURNING the updated rows. Both CTEs share the same snapshot and
        // transaction.
        //
        // Driving relation: subscriptions, not deliveries. Subscription-side predicates
        // (deliverability, circuit cooldown, throttle) are evaluated once per subscription in the
        // outer WHERE. Two LATERAL joins per subscription:
        //   a) a scalar remaining_allowance = GREATEST(0, effective_cap - already_in_flight)
        //   b) up to remaining_allowance of that subscription's oldest candidate deliveries,
        //      locked FOR UPDATE OF d SKIP LOCKED
        //
        // Delivery-side predicates (the four below) reproduce ADR-002 §2.1 verbatim, with
        // :as_of replacing now(), and are textually unchanged from before this task:
        //   1. status IN (PENDING, RETRYING, QUEUED, PROCESSING)
        //   2. next_attempt_at <= :as_of
        //   3. PENDING grace: d.created_at < :as_of - 30s
        //   4. PROCESSING staleness: d.updated_at < :as_of - 60s (crashed-worker reclaim)
        // Subscription-side predicates, evaluated once per subscription in the outer WHERE:
        //   5. Circuit cooldown: circuit_opened_at < :as_of - circuit_backoff (elapsed => probe)
        //   6. Throttle: throttled_until is null or past
        //   7. Deliverability gate (new): active AND verification_state = 'VERIFIED'
        String sql =
                "WITH claimed AS ("
                        + "  SELECT d.delivery_id"
                        + "  FROM subscriptions s"
                        + "  CROSS JOIN LATERAL ("
                        + "    SELECT GREATEST("
                        + "             0,"
                        + "             (CASE WHEN s.circuit_state = 'CLOSED' THEN s.max_concurrency ELSE 1 END)"
                        // Two-branch CASE, deliberately no 0 arm: a still-cooling OPEN circuit is
                        // already excluded by predicate 5 in the outer WHERE below, so any row that
                        // reaches this cap has circuit_state IN ('CLOSED', 'HALF_OPEN', or a cooled
                        // OPEN probe) — HALF_OPEN and a cooled OPEN probe both take the one-probe
                        // cap of 1 (ADR-006 §1.2). A third branch for OPEN would be dead code.
                        + "             - ("
                        + "                 SELECT count(*)"
                        + "                 FROM deliveries di"
                        + "                 WHERE di.subscription_id = s.subscription_id"
                        + "                   AND di.status IN ('QUEUED', 'PROCESSING')"
                        // Non-candidate in-flight rows only: a QUEUED/PROCESSING row this cycle's
                        // candidate predicate (below) would itself admit as reclaimable is excluded
                        // here, so it is never double-counted against the cap.
                        + "                   AND ("
                        + "                         di.next_attempt_at IS NULL"
                        + "                         OR di.next_attempt_at > :as_of"
                        + "                         OR (di.status = 'PROCESSING' AND di.updated_at >= :as_of - interval '60 seconds')"
                        + "                       )"
                        + "               )"
                        + "           ) AS remaining_allowance"
                        + "  ) a"
                        + "  CROSS JOIN LATERAL ("
                        + "    SELECT d.delivery_id, d.next_attempt_at"
                        + "    FROM deliveries d"
                        + "    WHERE d.subscription_id = s.subscription_id"
                        + "      AND d.status IN ('PENDING', 'RETRYING', 'QUEUED', 'PROCESSING')"
                        + "      AND d.next_attempt_at <= :as_of"
                        + "      AND (d.status <> 'PENDING'    OR d.created_at < :as_of - interval '30 seconds')"
                        + "      AND (d.status <> 'PROCESSING' OR d.updated_at < :as_of - interval '60 seconds')"
                        + "    ORDER BY d.next_attempt_at, d.delivery_id"
                        + "    LIMIT a.remaining_allowance"
                        + "    FOR UPDATE OF d SKIP LOCKED"
                        + "  ) d"
                        + "  WHERE s.active"
                        + "    AND s.verification_state = 'VERIFIED'"
                        + "    AND (s.circuit_state <> 'OPEN' OR s.circuit_opened_at < :as_of - s.circuit_backoff)"
                        + "    AND (s.throttled_until IS NULL OR s.throttled_until <= :as_of)"
                        + "  ORDER BY d.next_attempt_at, d.delivery_id"
                        + "  LIMIT :batch_limit"
                        + "),"
                        + "updated AS ("
                        + "  UPDATE deliveries"
                        + "     SET status          = 'QUEUED'::delivery_status,"
                        + "         next_attempt_at = :as_of + interval '" + CLAIM_DUE_PUSH_INTERVAL + "',"
                        + "         updated_at      = :as_of"
                        + "   WHERE delivery_id IN (SELECT delivery_id FROM claimed)"
                        + "   RETURNING "
                        + "     delivery_id, event_id, subscription_id, client_id, status, origin,"
                        + "     replayed_from, attempt_count, next_attempt_at, last_error,"
                        + "     delivered_at, event_created_at, trace_context"
                        + ")"
                        + "SELECT delivery_id, event_id, subscription_id, client_id, status, origin,"
                        + "     replayed_from, attempt_count, next_attempt_at, last_error,"
                        + "     delivered_at, event_created_at, trace_context"
                        + " FROM updated";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("as_of", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))
                .addValue("batch_limit", batchLimit);

        List<Delivery> result = jdbcTemplate.query(sql, params, insertRowMapper());
        return result == null ? Collections.emptyList() : result;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * A row mapper that reads columns without the {@code d.} alias prefix, used for
     * INSERT ... RETURNING and for findById (single-table SELECT).
     */
    private org.springframework.jdbc.core.RowMapper<Delivery> insertRowMapper() {
        return deliveryRowMapper;
    }
}
