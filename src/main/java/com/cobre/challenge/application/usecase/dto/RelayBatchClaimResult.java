package com.cobre.challenge.application.usecase.dto;

import com.cobre.challenge.domain.model.delivery.Delivery;
import java.util.List;
import java.util.Objects;

/** Claim-transaction outcome: rows claimed and how many distinct subscriptions were actually promoted OPEN -> HALF_OPEN. */
public record RelayBatchClaimResult(List<Delivery> claimed, int promotedCount) {

    public RelayBatchClaimResult {
        Objects.requireNonNull(claimed, "claimed must not be null (use List.of())");
        claimed = List.copyOf(claimed);
        if (promotedCount < 0) {
            throw new IllegalArgumentException("promotedCount must be >= 0, was " + promotedCount);
        }
    }
}
