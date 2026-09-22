package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.policy.Redaction;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/** One row of a delivery's attempt history, as returned by the detail endpoint. */
public record DeliveryAttemptResponse(
        int attemptNumber,
        OptionalInt httpStatus,
        int responseTimeMs,
        Optional<String> responseExcerpt,
        Optional<String> error,
        Instant attemptedAt) {

    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.attemptNumber(),
                attempt.httpStatus(),
                attempt.responseTimeMs(),
                attempt.responseExcerpt(),
                attempt.error(),
                attempt.attemptedAt());
    }

    /** ADR-008 §3.3: never print {@code responseExcerpt} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "DeliveryAttemptResponse[attemptNumber=%d, httpStatus=%s, responseTimeMs=%d, responseExcerpt=%s, error=%s, attemptedAt=%s]"
                .formatted(
                        attemptNumber, httpStatus, responseTimeMs, Redaction.redact(responseExcerpt), error,
                        attemptedAt);
    }
}
