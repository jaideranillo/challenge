package com.cobre.challenge.application.port.in.pipeline.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One claimed delivery's outcome, carried out so the relay adapter can open its own span (ADR-008 §2.3/§2.6). */
public record DispatchedDeliveryEntry(UUID deliveryId, Optional<String> traceparent, boolean published) {

    public DispatchedDeliveryEntry {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(traceparent, "traceparent must not be null (use Optional.empty())");
    }
}
