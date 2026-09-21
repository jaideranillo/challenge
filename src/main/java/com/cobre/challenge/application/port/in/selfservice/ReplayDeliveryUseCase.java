package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;

/**
 * {@code POST /notification_events/{id}/replay} (ADR-005 SS1). Accepting a
 * replay inserts a new row; it never mutates the original. Rejection is a
 * typed reason the controller maps to 409, never a bare boolean.
 */
public interface ReplayDeliveryUseCase {

    ReplayDeliveryResult replay(ReplayDeliveryCommand command);
}
