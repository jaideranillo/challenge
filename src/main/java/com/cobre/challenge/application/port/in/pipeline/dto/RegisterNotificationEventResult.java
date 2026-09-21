package com.cobre.challenge.application.port.in.pipeline.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * @param deliveryIds the created (or already-existing, on idempotent re-ingest) delivery ids;
 *                    empty when no active subscription matched (ADR-003 SS2)
 * @param newlyCreated true when this call created new rows, false when it returned an
 *                     existing live delivery for an already-ingested event (ADR-003 SS2)
 */
public record RegisterNotificationEventResult(List<UUID> deliveryIds, boolean newlyCreated) {

    public RegisterNotificationEventResult {
        Objects.requireNonNull(deliveryIds, "deliveryIds must not be null (use an empty list)");
        deliveryIds = List.copyOf(deliveryIds);
    }
}
