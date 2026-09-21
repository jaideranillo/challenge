package com.cobre.challenge.application.port.out.persistence.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Filter set for {@link com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort#findPage}
 * (ADR-005 Amendment D3).
 *
 * <p>Absence of a filter is {@link Optional#empty()}, never {@code null}.
 */
public record DeliveryPageQuery(
        Optional<Instant> eventCreatedFrom,
        Optional<Instant> eventCreatedTo,
        Optional<DeliveryStatus> status,
        Optional<String> cursor) {

    public DeliveryPageQuery {
        Objects.requireNonNull(eventCreatedFrom, "eventCreatedFrom must not be null");
        Objects.requireNonNull(eventCreatedTo, "eventCreatedTo must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(cursor, "cursor must not be null");
    }

    public static DeliveryPageQuery unfiltered() {
        return new DeliveryPageQuery(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
