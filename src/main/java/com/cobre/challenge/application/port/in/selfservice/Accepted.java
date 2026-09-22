package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * @param originalTraceContext the replayed (original, {@code DEAD}) delivery's persisted
 *     {@code trace_context}, if it has one - carried through so the adapter can attach a
 *     {@code notification.replay} span link to it (ADR-008 §2.5). {@link Optional#empty()} is
 *     normal, not an error: an original row ingested before this ADR landed, or with no active
 *     span at ingest, has none.
 */
public record Accepted(UUID newDeliveryId, DeliveryStatus status, Optional<String> originalTraceContext)
        implements ReplayDeliveryResult {

    public Accepted {
        Objects.requireNonNull(newDeliveryId, "newDeliveryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(originalTraceContext, "originalTraceContext must not be null (use Optional.empty())");
    }
}
