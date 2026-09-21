package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import java.util.Optional;

/**
 * {@code GET /notification_events/{id}} (ADR-005 SS1). Returns
 * {@link Optional#empty()} both when the row does not exist and when it
 * belongs to another client, so a 404 never leaks a cross-tenant hint.
 */
public interface GetNotificationEventUseCase {

    Optional<NotificationEventDetail> get(GetNotificationEventCommand command);
}
