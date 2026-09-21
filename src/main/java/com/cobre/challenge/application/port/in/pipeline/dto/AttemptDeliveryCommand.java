package com.cobre.challenge.application.port.in.pipeline.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AttemptDeliveryCommand(
        UUID deliveryId,
        UUID subscriptionId,
        int attemptHint,
        Optional<String> traceparent) {

    public AttemptDeliveryCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(traceparent, "traceparent must not be null (use Optional.empty())");
    }
}
