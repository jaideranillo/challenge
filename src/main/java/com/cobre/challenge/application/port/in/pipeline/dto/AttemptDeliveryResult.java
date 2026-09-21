package com.cobre.challenge.application.port.in.pipeline.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import java.util.Objects;

public record AttemptDeliveryResult(DeliveryStatus status, AttemptOutcome outcome) {

    public AttemptDeliveryResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
