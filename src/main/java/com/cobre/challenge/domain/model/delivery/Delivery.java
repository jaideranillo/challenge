package com.cobre.challenge.domain.model.delivery;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The delivery aggregate: domain-relevant state of one {@code deliveries}
 * row (ADR-003 SS3). Persistence-only columns ({@code created_at},
 * {@code updated_at}, {@code trace_context}) are adapter concerns and are
 * not represented here.
 */
public record Delivery(
        UUID deliveryId,
        String eventId,
        UUID subscriptionId,
        String clientId,
        DeliveryStatus status,
        DeliveryOrigin origin,
        Optional<UUID> replayedFrom,
        int attemptCount,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastError,
        Optional<Instant> deliveredAt) {

    public Delivery {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(replayedFrom, "replayedFrom must not be null (use Optional.empty())");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null (use Optional.empty())");
        Objects.requireNonNull(lastError, "lastError must not be null (use Optional.empty())");
        Objects.requireNonNull(deliveredAt, "deliveredAt must not be null (use Optional.empty())");

        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be >= 0, was " + attemptCount);
        }
        if (deliveredAt.isPresent() && status != DeliveryStatus.DELIVERED) {
            throw new IllegalArgumentException("deliveredAt may only be set when status is DELIVERED");
        }
    }

    public Delivery transitionTo(DeliveryStatus target) {
        DeliveryStatus next = status.transitionTo(target);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount, nextAttemptAt, lastError, deliveredAt);
    }

    public Delivery markDelivered(Instant at) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.DELIVERED);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount, Optional.empty(), lastError, Optional.of(at));
    }

    public Delivery markRetrying(Instant nextAttemptAt, String lastError) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.RETRYING);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount + 1, Optional.of(nextAttemptAt), Optional.of(lastError), deliveredAt);
    }

    public Delivery markDead(String lastError) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.DEAD);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount, Optional.empty(), Optional.of(lastError), deliveredAt);
    }
}
