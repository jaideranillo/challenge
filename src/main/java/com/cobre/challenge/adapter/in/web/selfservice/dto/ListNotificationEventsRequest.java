package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.bind.annotation.BindParam;

/**
 * {@code GET /notification_events} query parameters (ADR-005 §1). The wire names stay
 * snake_case via {@link BindParam}; the command's own field names
 * ({@code eventCreatedFrom}/{@code eventCreatedTo}, ADR-005 Amendment D2) are mapped only in
 * {@link #toCommand}. No clamping, windowing or filtering logic lives here.
 */
public record ListNotificationEventsRequest(
        @BindParam("created_from") Optional<Instant> createdFrom,
        @BindParam("created_to") Optional<Instant> createdTo,
        @BindParam("delivery_status") Optional<DeliveryStatus> deliveryStatus,
        Optional<String> cursor,
        Integer limit) {

    public ListNotificationEventsRequest {
        createdFrom = createdFrom == null ? Optional.empty() : createdFrom;
        createdTo = createdTo == null ? Optional.empty() : createdTo;
        deliveryStatus = deliveryStatus == null ? Optional.empty() : deliveryStatus;
        cursor = cursor == null ? Optional.empty() : cursor;
    }

    // Absent query param binds to null here (an int primitive would instead fail Spring's data
    // binder for a missing value). QueryNotificationEventsCommand's compact constructor rejects
    // limit <= 0 outright (queryCommandRejectsNonPositiveLimit), so the default page size must be
    // resolved to its real, positive value here - passing 0 through and relying on the use case's
    // dead "<= 0 means default" branch would just move the same IllegalArgumentException one
    // layer down instead of fixing it.
    public QueryNotificationEventsCommand toCommand(TenantId tenant, int defaultLimit) {
        int resolvedLimit = limit == null ? defaultLimit : limit;
        return new QueryNotificationEventsCommand(tenant, createdFrom, createdTo, deliveryStatus, cursor, resolvedLimit);
    }
}
