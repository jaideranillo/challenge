package com.cobre.challenge.application.port.in.pipeline.dto;

import com.cobre.challenge.domain.policy.Redaction;
import java.time.Instant;
import java.util.Objects;

public record RegisterNotificationEventCommand(
        String eventId,
        String clientId,
        String eventType,
        String content,
        Instant occurredAt) {

    public RegisterNotificationEventCommand {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /** ADR-008 §3.3: never print {@code content} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "RegisterNotificationEventCommand[eventId=%s, clientId=%s, eventType=%s, content=%s, occurredAt=%s]"
                .formatted(eventId, clientId, eventType, Redaction.redact(content), occurredAt);
    }
}
