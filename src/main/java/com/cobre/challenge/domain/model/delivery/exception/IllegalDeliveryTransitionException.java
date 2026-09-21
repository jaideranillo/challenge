package com.cobre.challenge.domain.model.delivery.exception;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;

/**
 * Thrown when a {@link DeliveryStatus} transition is not in the legal set
 * (ADR-003 SS1). Illegal transitions must fail loudly, never be swallowed.
 */
public class IllegalDeliveryTransitionException extends RuntimeException {

    private final DeliveryStatus from;
    private final DeliveryStatus to;

    public IllegalDeliveryTransitionException(DeliveryStatus from, DeliveryStatus to) {
        super("Illegal delivery status transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public DeliveryStatus from() {
        return from;
    }

    public DeliveryStatus to() {
        return to;
    }
}
