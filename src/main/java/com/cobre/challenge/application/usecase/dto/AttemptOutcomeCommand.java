package com.cobre.challenge.application.usecase.dto;

import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.TransportFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The already-classified facts of one delivery attempt (ADR-002 SS2.2 step 6). Nothing in
 * {@link com.cobre.challenge.application.usecase.DeliveryOutcomeWriter} re-classifies; this
 * command carries the {@link AttemptOutcome} as already computed by the response classifier or
 * the egress pre-flight.
 *
 * <p>{@code httpStatus} may legitimately be {@link OptionalInt#empty()}: a transport failure, an
 * egress policy rejection ({@code NON_RETRYABLE}), or a DNS failure ({@code RETRYABLE}) all occur
 * before any HTTP exchange (ADR-003 SS3).
 */
public record AttemptOutcomeCommand(
        UUID deliveryId,
        UUID subscriptionId,
        int attemptNumber,
        Instant attemptedAt,
        AttemptOutcome outcome,
        OptionalInt httpStatus,
        TransportFailure transportFailure,
        Optional<String> error,
        int responseTimeMs,
        Optional<String> responseExcerpt,
        Optional<Duration> retryAfter,
        boolean wasHalfOpenProbe,
        int currentAttemptCount) {

    public AttemptOutcomeCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(httpStatus, "httpStatus must not be null (use OptionalInt.empty())");
        Objects.requireNonNull(transportFailure, "transportFailure must not be null (use TransportFailure.NONE)");
        Objects.requireNonNull(error, "error must not be null (use Optional.empty())");
        Objects.requireNonNull(responseExcerpt, "responseExcerpt must not be null (use Optional.empty())");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null (use Optional.empty())");

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, was " + attemptNumber);
        }
        if (currentAttemptCount < 0) {
            throw new IllegalArgumentException("currentAttemptCount must be >= 0, was " + currentAttemptCount);
        }
    }
}
