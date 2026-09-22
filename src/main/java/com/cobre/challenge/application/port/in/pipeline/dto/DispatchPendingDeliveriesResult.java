package com.cobre.challenge.application.port.in.pipeline.dto;

import java.util.List;
import java.util.Objects;

public record DispatchPendingDeliveriesResult(int claimedCount, int publishedCount, List<DispatchedDeliveryEntry> entries) {

    public DispatchPendingDeliveriesResult {
        if (claimedCount < 0 || publishedCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        Objects.requireNonNull(entries, "entries must not be null (use List.of())");
        entries = List.copyOf(entries);
    }
}
