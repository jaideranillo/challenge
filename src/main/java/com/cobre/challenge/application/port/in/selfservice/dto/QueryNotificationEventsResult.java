package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.Delivery;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record QueryNotificationEventsResult(List<Delivery> deliveries, Optional<String> nextCursor) {

    public QueryNotificationEventsResult {
        Objects.requireNonNull(deliveries, "deliveries must not be null (use an empty list)");
        Objects.requireNonNull(nextCursor, "nextCursor must not be null (use Optional.empty())");
        deliveries = List.copyOf(deliveries);
    }
}
