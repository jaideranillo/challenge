package com.cobre.challenge.application.port.in.pipeline.dto;

public record DispatchPendingDeliveriesResult(int claimedCount, int publishedCount) {

    public DispatchPendingDeliveriesResult {
        if (claimedCount < 0 || publishedCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }
}
