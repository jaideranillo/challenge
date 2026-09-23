package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.PublicDeliveryStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One {@code GET /notification_events} list item, and the delivery half of the detail response.
 * Excludes {@code clientId} (the caller's own tenant, redundant on the wire) and
 * {@code traceContext} (operational metadata, not client data). {@code status} is the public
 * vocabulary (ADR-003 §1.1: the internal 7-state enum never reaches the wire) - so it round-trips
 * with the {@code delivery_status} filter on {@code GET /notification_events}.
 */
public record NotificationEventListItemResponse(
        UUID deliveryId,
        String eventId,
        UUID subscriptionId,
        PublicDeliveryStatus status,
        DeliveryOrigin origin,
        Optional<UUID> replayedFrom,
        int attemptCount,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastError,
        Optional<Instant> deliveredAt,
        Instant eventCreatedAt) {

    public static NotificationEventListItemResponse from(Delivery delivery) {
        return new NotificationEventListItemResponse(
                delivery.deliveryId(),
                delivery.eventId(),
                delivery.subscriptionId(),
                PublicDeliveryStatus.of(delivery.status()),
                delivery.origin(),
                delivery.replayedFrom(),
                delivery.attemptCount(),
                delivery.nextAttemptAt(),
                delivery.lastError(),
                delivery.deliveredAt(),
                delivery.eventCreatedAt());
    }
}
