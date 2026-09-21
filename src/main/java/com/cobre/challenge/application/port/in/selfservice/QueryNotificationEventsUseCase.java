package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;

/**
 * {@code GET /notification_events} (ADR-005 SS1): a keyset-paginated,
 * tenant-scoped list of deliveries.
 */
public interface QueryNotificationEventsUseCase {

    QueryNotificationEventsResult query(QueryNotificationEventsCommand command);
}
