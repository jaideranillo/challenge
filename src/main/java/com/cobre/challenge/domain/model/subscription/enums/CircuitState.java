package com.cobre.challenge.domain.model.subscription.enums;

/**
 * Per-subscription circuit-breaker state (ADR-006 SS1.2).
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
