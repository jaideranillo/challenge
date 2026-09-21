package com.cobre.challenge.application.port.in.pipeline.dto;

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
}
