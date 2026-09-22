package com.cobre.challenge.adapter.in.web.ingest.dto;

import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.domain.policy.Redaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * {@code POST /internal/events} request body. Every field is bounded (ADR-003 §3's columns are
 * all {@code text}/unbounded in Postgres, so the servlet default is the only ceiling absent one
 * here) and every ceiling below is a deliberate application-level choice, not a schema limit.
 */
public record IngestEventRequest(
        // 128 chars: comfortably covers a platform-assigned id (e.g. "EVT001") with headroom;
        // restricted to a safe id charset so it is never mistaken for a free-text field.
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9_.:-]+$") String eventId,
        // 128 chars: a tenant identifier, not free text.
        @NotBlank @Size(max = 128) String clientId,
        // 128 chars: a dot-separated event type name (e.g. "payment.created"), not free text.
        @NotBlank @Size(max = 128) String eventType,
        // 65536 chars (64 KiB): the producer-supplied payload. ADR-002 §3.1's PII layer; the
        // ceiling exists so one oversized event cannot dominate a batch or the wire size.
        @NotNull @Size(max = 65536) String content,
        @NotNull Instant occurredAt) {

    public RegisterNotificationEventCommand toCommand() {
        return new RegisterNotificationEventCommand(eventId, clientId, eventType, content, occurredAt);
    }

    /** ADR-008 §3.3: never print {@code content} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "IngestEventRequest[eventId=%s, clientId=%s, eventType=%s, content=%s, occurredAt=%s]"
                .formatted(eventId, clientId, eventType, Redaction.redact(content), occurredAt);
    }
}
