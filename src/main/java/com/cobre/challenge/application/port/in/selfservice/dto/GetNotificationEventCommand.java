package com.cobre.challenge.application.port.in.selfservice.dto;

import java.util.Objects;
import java.util.UUID;

public record GetNotificationEventCommand(UUID deliveryId, String clientId) {

    public GetNotificationEventCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
    }
}
