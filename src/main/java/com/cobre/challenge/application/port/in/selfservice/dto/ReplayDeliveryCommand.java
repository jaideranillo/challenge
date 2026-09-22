package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.Objects;
import java.util.UUID;

public record ReplayDeliveryCommand(UUID deliveryId, TenantId tenant, String idempotencyKey) {

    public ReplayDeliveryCommand {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}
