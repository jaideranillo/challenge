package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import com.cobre.challenge.domain.policy.Redaction;
import java.util.List;
import java.util.Objects;

/**
 * {@code GET /notification_events/{id}} response body: the delivery, the event body and the
 * full attempt history, unpaged and uncapped (ADR-005 §1).
 */
public record NotificationEventDetailResponse(
        NotificationEventListItemResponse delivery,
        String eventId,
        String eventType,
        String content,
        List<DeliveryAttemptResponse> attempts) {

    public NotificationEventDetailResponse {
        Objects.requireNonNull(delivery, "delivery must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(attempts, "attempts must not be null (use an empty list)");
        attempts = List.copyOf(attempts);
    }

    public static NotificationEventDetailResponse from(NotificationEventDetail detail) {
        List<DeliveryAttemptResponse> attempts =
                detail.attempts().stream().map(DeliveryAttemptResponse::from).toList();
        return new NotificationEventDetailResponse(
                NotificationEventListItemResponse.from(detail.delivery()),
                detail.notificationEvent().eventId(),
                detail.notificationEvent().eventType(),
                detail.notificationEvent().content(),
                attempts);
    }

    /** ADR-008 §3.3: never print {@code content} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "NotificationEventDetailResponse[delivery=%s, eventId=%s, eventType=%s, content=%s, attempts=%s]"
                .formatted(delivery, eventId, eventType, Redaction.redact(content), attempts);
    }
}
