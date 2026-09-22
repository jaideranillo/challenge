package com.cobre.challenge.application.port.out.webhook.dto;

import com.cobre.challenge.domain.policy.Redaction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The ADR-004 SS1.1 outbound webhook body: seven fields, snake_case on the wire, in this order.
 * {@code notificationEventId} is {@code deliveries.delivery_id} (ADR-003 SS3's naming note) —
 * the wire key is {@code notification_event_id}, never {@code delivery_id}. {@code createdAt}
 * is {@code notification_events.created_at}, not a clock reading. {@code attempt} is the
 * attempt number of this attempt (authoritative {@code attempt_count} + 1), computed by the
 * caller.
 */
public record WebhookEnvelope(
        @JsonProperty("notification_event_id") UUID notificationEventId,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("content") String content) {

    public WebhookEnvelope {
        Objects.requireNonNull(notificationEventId, "notificationEventId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
    }

    /** ADR-008 §3.3: never print {@code content} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "WebhookEnvelope[notificationEventId=%s, eventId=%s, eventType=%s, clientId=%s, createdAt=%s, attempt=%d, content=%s]"
                .formatted(notificationEventId, eventId, eventType, clientId, createdAt, attempt, Redaction.redact(content));
    }
}
