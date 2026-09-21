package com.cobre.challenge.domain.model.delivery;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The delivery aggregate: domain-relevant state of one {@code deliveries} row (ADR-003 §3).
 *
 * <p>A field belongs in the aggregate when a use case reads or writes it (ADR-003 Amendment A3).
 * Under that rule:
 *
 * <ul>
 *   <li>{@code trace_context} — written by the ingest use case, read by the worker as the W3C
 *       traceparent fallback (ADR-002 §3.1) — is represented as {@link #traceContext}.
 *   <li>{@code event_created_at} — denormalized event timestamp, used by the query use case's
 *       date filter and keyset (ADR-005 §1) — is represented as {@link #eventCreatedAt}.
 * </ul>
 *
 * Persistence-only audit columns ({@code created_at}, {@code updated_at}) are adapter concerns
 * and are not represented here.
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
        Optional<Instant> deliveredAt,
        Instant eventCreatedAt,
        Optional<String> traceContext) {

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
        Objects.requireNonNull(eventCreatedAt, "eventCreatedAt must not be null");
        Objects.requireNonNull(traceContext, "traceContext must not be null (use Optional.empty())");

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
                replayedFrom, attemptCount, nextAttemptAt, lastError, deliveredAt,
                eventCreatedAt, traceContext);
    }

    public Delivery markDelivered(Instant at) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.DELIVERED);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount, Optional.empty(), lastError, Optional.of(at),
                eventCreatedAt, traceContext);
    }

    public Delivery markRetrying(Instant nextAttemptAt, String lastError) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.RETRYING);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount + 1, Optional.of(nextAttemptAt), Optional.of(lastError), deliveredAt,
                eventCreatedAt, traceContext);
    }

    public Delivery markDead(String lastError) {
        DeliveryStatus next = status.transitionTo(DeliveryStatus.DEAD);
        return new Delivery(deliveryId, eventId, subscriptionId, clientId, next, origin,
                replayedFrom, attemptCount, Optional.empty(), Optional.of(lastError), deliveredAt,
                eventCreatedAt, traceContext);
    }
}
