package com.cobre.challenge.application.port.in.selfservice.dto;

/**
 * The typed rejection reasons a replay attempt can fail with.
 * {@link #TARGET_NOT_DEAD} and {@link #LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS} are mapped by the
 * controller to 409 (ADR-005 SS1); {@link #TARGET_NOT_FOUND} is mapped to 404 (ADR-007 SS5.5) and
 * covers both a nonexistent id and another tenant's id, indistinguishably.
 */
public enum RejectionReason {
    TARGET_NOT_DEAD,
    LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS,
    TARGET_NOT_FOUND
}
