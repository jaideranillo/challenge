package com.cobre.challenge.application.port.out.queue.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Outcome of {@code NotificationQueuePort.publishBatch}; failedDeliveryIds is observability-only, never retried (ADR-002 SS2.1). */
public record PublishBatchResult(int publishedCount, List<UUID> failedDeliveryIds) {

    public PublishBatchResult {
        if (publishedCount < 0) {
            throw new IllegalArgumentException("publishedCount must not be negative");
        }
        Objects.requireNonNull(failedDeliveryIds, "failedDeliveryIds must not be null (use List.of())");
        failedDeliveryIds = List.copyOf(failedDeliveryIds);
    }
}
