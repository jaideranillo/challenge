package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence contract for the delivery pipeline (ADR-003 §3, ADR-002 §2.2).
 *
 * <p>This port is cross-tenant by design: no method carries a {@code clientId} parameter.
 * The worker runs with no principal and needs to reach rows it claimed without knowing the
 * originating tenant. This is the carve-out ADR-007 §5.2 grants to internal pipeline ports
 * and is made visible in the type system by the split from {@code DeliveryQueryRepositoryPort}
 * (ADR-007 Amendment E1).
 *
 * <p>Every conditional write returns {@code boolean} derived from the affected-row count.
 * Zero rows affected is a normal, expected outcome — a duplicate message or a lost race — and
 * must never throw (ADR-002 §2.2 step 2).
 */
public interface DeliveryPipelineRepositoryPort {

    /**
     * Inserts a new delivery row.
     *
     * <p>Writes every column, including {@code trace_context} (ADR-003 Amendment A3) and
     * {@code event_created_at} (ADR-003 Amendment A4), both of which arrive on the
     * {@link Delivery} aggregate. The partial unique index on {@code (event_id, subscription_id)}
     * enforces idempotency (ADR-003 §2). Used by ingest, replay, and recovery.
     *
     * @return the inserted {@link Delivery} as persisted
     */
    Delivery insert(Delivery delivery);

    /**
     * Inserts a new delivery row unless a live row already exists for {@code (event_id,
     * subscription_id)}, in which case the conflict is reported as {@code Optional.empty()}
     * rather than thrown.
     *
     * <p>{@code Optional.empty()} means a live row already exists for
     * {@code (event_id, subscription_id)} — the idempotent-replay outcome, not a failure.
     * "Live" is exactly {@code idx_deliveries_live_pair}'s predicate:
     * {@code status NOT IN ('DELIVERED', 'DEAD', 'FAILED')}. This method and
     * {@link #findLiveByEventAndSubscription(String, UUID)} must agree on that definition, or
     * a conflict here could return no row on the follow-up read.
     *
     * <p>{@link #insert(Delivery)} is kept, unchanged, and is not deprecated: {@code POST
     * /replay} and internal recovery (ADR-005 §1) must fail loudly when the pair is still
     * live, so they keep calling {@code insert}. Ingest is the only caller that wants the
     * conflict swallowed, which is why it gets its own method instead of a flag on
     * {@code insert} (ADR-003 §2, Amendment A5).
     *
     * @return the inserted {@link Delivery} as persisted, or {@code Optional.empty()} on a
     *     live-pair conflict
     */
    Optional<Delivery> insertIfAbsent(Delivery delivery);

    /**
     * Loads the row the worker just claimed, without a tenant filter.
     *
     * <p>The worker must read {@code event_id}, {@code subscription_id}, the authoritative
     * {@code attempt_count}, and the persisted {@code trace_context} immediately after
     * claiming a row (ADR-002 §2.2 steps 5-6). It runs with no principal, so no
     * {@code client_id} predicate is possible or correct here. ADR-007 §5.2's "there is no
     * unscoped {@code findById}" governs {@link DeliveryQueryRepositoryPort}, the client-facing
     * port; it does not apply here. See ADR-007 Amendment E1.
     */
    Optional<Delivery> findById(UUID deliveryId);

    /**
     * Loads the live row, if any, for {@code (event_id, subscription_id)}.
     *
     * <p>"Live" is exactly {@code idx_deliveries_live_pair}'s predicate:
     * {@code status NOT IN ('DELIVERED', 'DEAD', 'FAILED')}. This must agree with
     * {@link #insertIfAbsent(Delivery)}'s definition of live, or a conflict there could
     * return no row here. The unique partial index guarantees at most one live row per pair,
     * which is why the return is {@link Optional} and not {@link List}.
     *
     * <p>Cross-tenant like every other method on this port: no {@code clientId} parameter.
     */
    Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId);

    /**
     * Claims the delivery for processing: {@code status = 'QUEUED' -> 'PROCESSING'}.
     *
     * <p><strong>Safety-critical.</strong> This is the only thing preventing a double POST
     * (ADR-003 Q3 resolution). The guard is not a convenience check bolted on; the
     * {@code QUEUED} precondition <em>is</em> the operation. Writes {@code status} and
     * {@code updated_at}.
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean claimForProcessing(UUID deliveryId, Instant now);

    /**
     * Records a successful delivery: {@code status = 'PROCESSING' -> 'DELIVERED'}.
     *
     * <p>Guard: {@code status = 'PROCESSING'}. Writes {@code status}, {@code delivered_at},
     * {@code next_attempt_at = NULL}, and {@code updated_at} (ADR-003 §1.1).
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean markDelivered(UUID deliveryId, Instant deliveredAt);

    /**
     * Schedules a retry: {@code status = 'PROCESSING' -> 'RETRYING'}.
     *
     * <p>Guard: {@code status = 'PROCESSING'}. Writes {@code status}, {@code attempt_count + 1},
     * {@code next_attempt_at}, {@code last_error}, and {@code updated_at}
     * (ADR-003 §1.1, ADR-004 §1).
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now);

    /**
     * Marks a delivery permanently dead: {@code status = 'PROCESSING' -> 'DEAD'}.
     *
     * <p>Guard: {@code status = 'PROCESSING'}. Writes {@code status},
     * {@code next_attempt_at = NULL}, {@code last_error}, and {@code updated_at}
     * (ADR-003 §1.1).
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean markDead(UUID deliveryId, String lastError, Instant now);

    /**
     * Marks a delivery failed from a DLQ message.
     *
     * <p>Guard: {@code status IN ('QUEUED', 'PROCESSING')} — a DLQ message arrives without
     * knowledge of which state the row is in, because the state machine admits both
     * {@code QUEUED -> FAILED} and {@code PROCESSING -> FAILED} (see
     * {@code DeliveryStatus.legalTargets()}). The three terminal states
     * ({@code DELIVERED}, {@code DEAD}, {@code FAILED}) are excluded so a late DLQ message
     * cannot overwrite a row that already reached a terminal state (ADR-003 §1.1).
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean markFailed(UUID deliveryId, String lastError, Instant now);

    /**
     * Defers a queued delivery to a later instant: {@code status} unchanged.
     *
     * <p>Guard: {@code status = 'QUEUED'}. Writes {@code next_attempt_at} only.
     * <strong>Must not</strong> increment {@code attempt_count}, write {@code last_error},
     * insert a {@code delivery_attempts} row, or change {@code status} — no attempt
     * occurred (ADR-002 §2.2 step 3). {@code updated_at} comes from the database clock in
     * the adapter; this method deliberately takes no {@code Instant now} parameter to prevent
     * callers from expressing those forbidden writes as a side-channel (concern logged in
     * {@code docs/concerns.md}).
     *
     * @return {@code true} when exactly one row was updated
     */
    boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt);

    /**
     * Claims due deliveries, capped per subscription before {@code batchLimit} applies
     * (ADR-002 §2.1, ADR-005 §2 deliverability gate, ADR-006 §1.2 in-flight cap: 0 OPEN /
     * 1 HALF_OPEN / max_concurrency CLOSED).
     */
    List<Delivery> claimDue(int batchLimit, Instant asOf);
}
