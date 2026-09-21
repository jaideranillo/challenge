package com.cobre.challenge.application.port.in.pipeline.dto;

import java.time.Instant;
import java.util.Objects;

public record DispatchPendingDeliveriesCommand(int batchLimit, Instant asOf) {

    public DispatchPendingDeliveriesCommand {
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("batchLimit must be positive, was " + batchLimit);
        }
    }
}
