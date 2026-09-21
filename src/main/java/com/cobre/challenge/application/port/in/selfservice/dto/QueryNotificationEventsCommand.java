package com.cobre.challenge.application.port.in.selfservice.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * @param clientId mandatory: the authenticated caller's tenant, taken from the security
 *                 context — the query is always scoped to it (ADR-005 SS1)
 */
public record QueryNotificationEventsCommand(
        String clientId,
        Optional<Instant> createdFrom,
        Optional<Instant> createdTo,
        Optional<DeliveryStatus> status,
        Optional<String> cursor,
        int limit) {

    public QueryNotificationEventsCommand {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(createdFrom, "createdFrom must not be null (use Optional.empty())");
        Objects.requireNonNull(createdTo, "createdTo must not be null (use Optional.empty())");
        Objects.requireNonNull(status, "status must not be null (use Optional.empty())");
        Objects.requireNonNull(cursor, "cursor must not be null (use Optional.empty())");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
    }
}
