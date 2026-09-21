package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.subscription.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence contract for {@code subscriptions} (ADR-003 §3).
 *
 * <p>This interface is cross-tenant throughout: no method carries a {@code clientId} parameter.
 * {@code clientId} is part of the query predicate on the fan-out lookup ({@link
 * #findActiveForEvent}), not a post-hoc filter (ADR-003 §2) — the single point where a
 * cross-tenant leak could originate.
 *
 * <p>Every conditional write returns {@code boolean} derived from the affected-row count.
 * Zero rows affected is a normal, expected outcome and must never throw.
 */
public interface SubscriptionRepositoryPort {

    List<Subscription> findActiveForEvent(String clientId, String eventType);

    Optional<Subscription> findById(UUID subscriptionId);

    /** 404/410 classification outcome (ADR-004 §1). Conditional write; returns whether one row was affected. */
    boolean deactivate(UUID subscriptionId);

    /** 429 classification outcome (ADR-004 §1). Conditional write; returns whether one row was affected. */
    boolean setThrottledUntil(UUID subscriptionId, Instant throttledUntil);

    /**
     * Opens the circuit breaker for a healthy destination that has started failing.
     *
     * <p>Guard: {@code circuit_state = 'CLOSED'}. Caller: the worker, when its pod-local
     * Resilience4j breaker trips. Writes {@code circuit_state = 'OPEN'},
     * {@code circuit_opened_at = now}, {@code consecutive_opens + 1}, and computes
     * {@code circuit_backoff = baseCooldown * 2^consecutive_opens} (capped at
     * {@code maxCooldown}).
     *
     * <p><strong>Pre-update exponent (ADR-006 Amendment B3):</strong> the exponent uses the
     * column's pre-update value, because a PostgreSQL {@code UPDATE}'s right-hand side reads
     * the pre-update row. A first trip has {@code consecutive_opens = 0}, so the backoff is
     * {@code base * 2^0 = base}. Writing {@code consecutive_opens + 1} on the right-hand side
     * would silently double every cooldown interval.
     *
     * <p>The {@code SET} clause written by this method is identical to that of
     * {@link #reopenCircuit}, and the two are deliberately separate operations because their
     * guards are not (ADR-006 Amendment B2). A single generic method accepting the expected
     * state as a parameter would allow a caller to apply a trip precondition to a
     * {@code HALF_OPEN} circuit, double-counting a cooldown escalation.
     *
     * @param baseCooldown minimum backoff interval; the adapter computes the actual backoff in SQL
     * @param maxCooldown  upper cap on the computed backoff
     * @return {@code true} when exactly one row was transitioned
     */
    boolean tripCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now);

    /**
     * Re-opens the circuit breaker after a failed probe against a recovering destination.
     *
     * <p>Guard: {@code circuit_state = 'HALF_OPEN'}. Caller: the worker, in the probe's
     * outcome transaction (ADR-002 §2.2 step 6). A failed probe depends on this guard:
     * applying it to a {@code CLOSED} circuit would trip a destination nothing is currently
     * failing against.
     *
     * <p>The {@code SET} clause written by this method is identical to that of
     * {@link #tripCircuit} — both write {@code circuit_state = 'OPEN'},
     * {@code circuit_opened_at = now}, {@code consecutive_opens + 1}, and
     * {@code circuit_backoff = baseCooldown * 2^consecutive_opens} — and the two are
     * deliberately separate operations because their guards are not (ADR-006 Amendment B2).
     *
     * <p><strong>Pre-update exponent (ADR-006 Amendment B3):</strong> the exponent uses the
     * column's pre-update value. See {@link #tripCircuit} for the full explanation.
     *
     * @param baseCooldown minimum backoff interval; the adapter computes the actual backoff in SQL
     * @param maxCooldown  upper cap on the computed backoff
     * @return {@code true} when exactly one row was transitioned
     */
    boolean reopenCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now);

    /**
     * Promotes the circuit to half-open after the cooldown has elapsed.
     *
     * <p>Guard: {@code circuit_state = 'OPEN' AND circuit_opened_at < asOf - circuit_backoff}.
     * The compound guard ensures the row is only promoted if the cooldown interval has actually
     * passed. {@code asOf} is a parameter so one relay poll cycle evaluates every subscription
     * against one instant, avoiding clock drift across rows (ADR-002 Amendment C2).
     *
     * <p>Writes {@code circuit_state = 'HALF_OPEN'} only.
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean promoteToHalfOpen(UUID subscriptionId, Instant asOf);

    /**
     * Closes the circuit breaker after a successful probe.
     *
     * <p>Guard: {@code circuit_state = 'HALF_OPEN'}. Resets all four circuit columns:
     * {@code circuit_state = 'CLOSED'}, {@code circuit_opened_at = NULL},
     * {@code circuit_backoff = NULL}, {@code consecutive_opens = 0}.
     *
     * @return {@code true} when exactly one row was transitioned
     */
    boolean closeCircuit(UUID subscriptionId, Instant now);
}
