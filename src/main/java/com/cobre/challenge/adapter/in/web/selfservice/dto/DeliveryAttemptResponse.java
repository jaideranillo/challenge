package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
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
}
