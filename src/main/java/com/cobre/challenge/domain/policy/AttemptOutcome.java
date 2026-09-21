package com.cobre.challenge.domain.policy;

/**
 * The classified result of one delivery attempt (ADR-004 SS1). Each value
 * carries whether it counts toward the subscription's circuit breaker.
 */
public enum AttemptOutcome {
    SUCCESS(false),
    RETRYABLE(true),
    RETRYABLE_THROTTLED(false),
    NON_RETRYABLE(false),
    NON_RETRYABLE_REDIRECT(true),
    NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION(false);

    private final boolean countsTowardCircuitBreaker;

    AttemptOutcome(boolean countsTowardCircuitBreaker) {
        this.countsTowardCircuitBreaker = countsTowardCircuitBreaker;
    }

    public boolean countsTowardCircuitBreaker() {
        return countsTowardCircuitBreaker;
    }
}
