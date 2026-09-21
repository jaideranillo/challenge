package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.Subscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence contract for {@code subscriptions} (ADR-003 SS3).
 * {@code clientId} is part of the query predicate on the fan-out lookup, not
 * a post-hoc filter (ADR-003 SS2) — the single point where a cross-tenant
 * leak could originate.
 */
public interface SubscriptionRepositoryPort {

    List<Subscription> findActiveForEvent(String clientId, String eventType);

    Optional<Subscription> findById(UUID subscriptionId);

    /** 404/410 classification outcome (ADR-004 SS1). Conditional write; returns whether one row was affected. */
    boolean deactivate(UUID subscriptionId);

    /** 429 classification outcome (ADR-004 SS1). Conditional write; returns whether one row was affected. */
    boolean setThrottledUntil(UUID subscriptionId, Instant throttledUntil);

    /** Circuit-breaker transition (ADR-006 SS1.2). Conditional write; returns whether one row was affected. */
    boolean transitionCircuitState(
            UUID subscriptionId,
            CircuitState expected,
            CircuitState target,
            Instant now);
}
