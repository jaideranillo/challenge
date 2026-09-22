package com.cobre.challenge.domain.model.delivery;

import com.cobre.challenge.domain.policy.Redaction;
import com.cobre.challenge.domain.policy.ResponseClassifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One row of the append-only {@code delivery_attempts} history (ADR-003 SS3).
 * No {@code outcome} component: the outcome is derived by
 * {@link ResponseClassifier}, not stored.
 */
public record DeliveryAttempt(
        UUID deliveryId,
        int attemptNumber,
        OptionalInt httpStatus,
        int responseTimeMs,
        Optional<String> responseExcerpt,
        Optional<String> error,
        Instant attemptedAt) {

    public DeliveryAttempt {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(httpStatus, "httpStatus must not be null (use OptionalInt.empty())");
        Objects.requireNonNull(responseExcerpt, "responseExcerpt must not be null (use Optional.empty())");
        Objects.requireNonNull(error, "error must not be null (use Optional.empty())");
        Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
    }

    /** ADR-008 §3.3: never print {@code responseExcerpt} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "DeliveryAttempt[deliveryId=%s, attemptNumber=%d, httpStatus=%s, responseTimeMs=%d, responseExcerpt=%s, error=%s, attemptedAt=%s]"
                .formatted(
                        deliveryId, attemptNumber, httpStatus, responseTimeMs, Redaction.redact(responseExcerpt),
                        error, attemptedAt);
    }
}
