package com.cobre.challenge.application.port.in.selfservice.dto;

/**
 * The typed rejection reasons a replay attempt can fail with, mapped by the controller
 * to 409 (ADR-005 SS1).
 */
public enum RejectionReason {
    TARGET_NOT_DEAD,
    LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS
}
