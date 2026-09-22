package com.cobre.challenge.domain.model.event;

import com.cobre.challenge.domain.policy.Redaction;
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

    /** ADR-008 §3.3: never print {@code content} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "NotificationEvent[eventId=%s, clientId=%s, eventType=%s, content=%s, createdAt=%s]"
                .formatted(eventId, clientId, eventType, Redaction.redact(content), createdAt);
    }
}
