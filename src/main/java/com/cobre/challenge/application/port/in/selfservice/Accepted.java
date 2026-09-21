package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;

import java.util.Objects;
import java.util.UUID;

public record Accepted(UUID newDeliveryId, DeliveryStatus status) implements ReplayDeliveryResult {

    public Accepted {
        Objects.requireNonNull(newDeliveryId, "newDeliveryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
