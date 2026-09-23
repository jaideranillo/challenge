package com.cobre.challenge.adapter.in.web.selfservice.dto;

import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.domain.model.delivery.enums.PublicDeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.bind.annotation.BindParam;

/**
 * {@code GET /notification_events} query parameters (ADR-005 §1). The wire names stay
 * snake_case via {@link BindParam}; the command's own field names
 * ({@code eventCreatedFrom}/{@code eventCreatedTo}, ADR-005 Amendment D2) are mapped only in
 * {@link #toCommand}. No clamping or windowing logic lives here.
 *
 * <p>{@code deliveryStatus} binds as a raw {@link String}, not {@link PublicDeliveryStatus}
 * directly: letting Spring's enum-binding machinery attempt the conversion and fail is what
 * produced a stray 403 for a malformed value (the servlet container's internal error dispatch for
 * an unresolved {@code MethodArgumentTypeMismatchException} re-enters Spring Security's filter
 * chain, matches no client-API chain, and falls through to the terminal {@code denyAll()}) - the
 * same failure mode already fixed for the {@code notification_event_id} path variable in
 * {@code NotificationEventController}. Parsing happens in the controller instead, where an
 * invalid value can be answered with a clean 400.
 */
public record ListNotificationEventsRequest(
        @BindParam("created_from") Optional<Instant> createdFrom,
        @BindParam("created_to") Optional<Instant> createdTo,
        @BindParam("delivery_status") Optional<String> deliveryStatus,
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
    public QueryNotificationEventsCommand toCommand(
            TenantId tenant, int defaultLimit, Optional<PublicDeliveryStatus> status) {
        int resolvedLimit = limit == null ? defaultLimit : limit;
        return new QueryNotificationEventsCommand(tenant, createdFrom, createdTo, status, cursor, resolvedLimit);
    }
}
