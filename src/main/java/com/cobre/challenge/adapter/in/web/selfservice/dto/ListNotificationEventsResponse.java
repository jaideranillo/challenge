package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@code GET /notification_events} response body: the page's items plus the next cursor. */
public record ListNotificationEventsResponse(
        List<NotificationEventListItemResponse> items, Optional<String> nextCursor) {

    public ListNotificationEventsResponse {
        Objects.requireNonNull(items, "items must not be null (use an empty list)");
        Objects.requireNonNull(nextCursor, "nextCursor must not be null (use Optional.empty())");
        items = List.copyOf(items);
    }

    public static ListNotificationEventsResponse from(QueryNotificationEventsResult result) {
        List<NotificationEventListItemResponse> items =
                result.deliveries().stream().map(NotificationEventListItemResponse::from).toList();
        return new ListNotificationEventsResponse(items, result.nextCursor());
    }
}
