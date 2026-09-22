package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * @param tenant mandatory: the authenticated caller's tenant, taken from the security
 *               context — the query is always scoped to it (ADR-005 SS1)
 * @param eventCreatedFrom bounds {@code deliveries.event_created_at}, not the row's own
 *                          {@code created_at} (ADR-005 Amendment D2)
 * @param eventCreatedTo bounds {@code deliveries.event_created_at}, not the row's own
 *                        {@code created_at} (ADR-005 Amendment D2)
 */
public record QueryNotificationEventsCommand(
        TenantId tenant,
        Optional<Instant> eventCreatedFrom,
        Optional<Instant> eventCreatedTo,
        Optional<DeliveryStatus> status,
        Optional<String> cursor,
        int limit) {

    public QueryNotificationEventsCommand {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(eventCreatedFrom, "eventCreatedFrom must not be null (use Optional.empty())");
        Objects.requireNonNull(eventCreatedTo, "eventCreatedTo must not be null (use Optional.empty())");
        Objects.requireNonNull(status, "status must not be null (use Optional.empty())");
        Objects.requireNonNull(cursor, "cursor must not be null (use Optional.empty())");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
    }
}
