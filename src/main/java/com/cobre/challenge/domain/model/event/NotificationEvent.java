package com.cobre.challenge.domain.model.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, append-only record of what the platform emitted (ADR-003 SS3).
 * Never updated after insert.
 */
public record NotificationEvent(
        String eventId,
        String clientId,
        String eventType,
        String content,
        Instant createdAt) {

    public NotificationEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
