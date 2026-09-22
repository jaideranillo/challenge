package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.Objects;
import java.util.UUID;

public record GetNotificationEventCommand(UUID deliveryId, TenantId tenant) {

    public GetNotificationEventCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
    }
}
