package com.cobre.challenge.domain.model.delivery.enums;

/**
 * How a {@code deliveries} row came to exist (ADR-003 SS3, ADR-005 SS3).
 */
public enum DeliveryOrigin {
    INGEST,
    REPLAY,
    RECOVERED
}
