package com.cobre.challenge.application.port.in.pipeline.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import java.util.Objects;
import java.util.Optional;

/**
 * @param status the status this call left the row in; {@code QUEUED} when this call changed
 *     nothing (a lost claim or a deferral)
 * @param outcome empty iff no HTTP attempt was made (lost claim, bulkhead deferral, open-circuit
 *     deferral); present in every other case, including pre-HTTP egress-validator classifications
 */
public record AttemptDeliveryResult(DeliveryStatus status, Optional<AttemptOutcome> outcome) {

    public AttemptDeliveryResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** No HTTP attempt was made; {@code status} is the row's unchanged status. */
    public static AttemptDeliveryResult noAttempt(DeliveryStatus status) {
        return new AttemptDeliveryResult(status, Optional.empty());
    }

    /** An HTTP attempt was made (or a pre-HTTP classification stands in for one). */
    public static AttemptDeliveryResult of(DeliveryStatus status, AttemptOutcome outcome) {
        return new AttemptDeliveryResult(status, Optional.of(outcome));
    }
}
