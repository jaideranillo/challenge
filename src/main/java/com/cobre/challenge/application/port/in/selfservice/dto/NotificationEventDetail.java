package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.util.List;
import java.util.Objects;

public record NotificationEventDetail(
        Delivery delivery,
        NotificationEvent notificationEvent,
        List<DeliveryAttempt> attempts) {

    public NotificationEventDetail {
        Objects.requireNonNull(delivery, "delivery must not be null");
        Objects.requireNonNull(notificationEvent, "notificationEvent must not be null");
        Objects.requireNonNull(attempts, "attempts must not be null (use an empty list)");
        attempts = List.copyOf(attempts);
    }
}
