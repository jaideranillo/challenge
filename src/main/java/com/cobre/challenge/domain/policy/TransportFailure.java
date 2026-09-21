package com.cobre.challenge.domain.policy;

/**
 * A transport-level failure that occurred before any HTTP response was
 * received (ADR-004 SS1). {@link #NONE} means a response was received and
 * {@code statusCode} should be read.
 */
public enum TransportFailure {
    NONE,
    TIMEOUT,
    CONNECTION_RESET,
    DNS_FAILURE,
    TLS_FAILURE
}
