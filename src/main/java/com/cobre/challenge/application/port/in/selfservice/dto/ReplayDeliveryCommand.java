package com.cobre.challenge.application.port.in.selfservice.dto;

import java.util.Objects;
import java.util.UUID;

public record ReplayDeliveryCommand(UUID deliveryId, String clientId, String idempotencyKey) {

    public ReplayDeliveryCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}
