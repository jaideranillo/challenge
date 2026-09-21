package com.cobre.challenge.domain.model.subscription;

import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery contract with a client (ADR-003 SS3), domain-relevant fields
 * only. Circuit-breaker transition logic and SSRF/URL validation are not
 * modeled here (adapter/later-feature concerns).
 */
public record Subscription(
        UUID subscriptionId,
        String clientId,
        String targetUrl,
        String secretRef,
        Optional<String> previousSecretRef,
        Optional<Instant> previousSecretExpiresAt,
        Set<String> eventTypes,
        boolean active,
        VerificationState verificationState,
        int maxConcurrency,
        CircuitState circuitState,
        Optional<Instant> throttledUntil) {

    public Subscription {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(targetUrl, "targetUrl must not be null");
        Objects.requireNonNull(secretRef, "secretRef must not be null");
        Objects.requireNonNull(previousSecretRef, "previousSecretRef must not be null (use Optional.empty())");
        Objects.requireNonNull(previousSecretExpiresAt,
                "previousSecretExpiresAt must not be null (use Optional.empty())");
        Objects.requireNonNull(eventTypes, "eventTypes must not be null");
        Objects.requireNonNull(verificationState, "verificationState must not be null");
        Objects.requireNonNull(circuitState, "circuitState must not be null");
        Objects.requireNonNull(throttledUntil, "throttledUntil must not be null (use Optional.empty())");

        eventTypes = Set.copyOf(eventTypes);
    }

    public boolean isDeliverable() {
        return active && verificationState == VerificationState.VERIFIED;
    }
}
