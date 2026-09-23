package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.application.port.in.selfservice.Accepted;
import com.cobre.challenge.domain.model.delivery.enums.PublicDeliveryStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code POST /notification_events/{id}/replay} 202 body: an acknowledgment, not an outcome
 * (ADR-005 §1). Carries the newly-created row's id, which is now the delivery to poll; the
 * original {@code DEAD} row and its history are untouched. {@code status} is the public
 * vocabulary (ADR-003 §1.1: the internal {@link com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus}
 * enum never reaches the wire).
 */
public record ReplayAcceptedResponse(UUID deliveryId, PublicDeliveryStatus status) {

    public ReplayAcceptedResponse {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static ReplayAcceptedResponse from(Accepted accepted) {
        return new ReplayAcceptedResponse(accepted.newDeliveryId(), PublicDeliveryStatus.of(accepted.status()));
    }
}
