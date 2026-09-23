package com.cobre.challenge.application.port.out.persistence.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Filter set for {@link com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort#findPage}
 * (ADR-005 Amendment D3).
 *
 * <p>Absence of a filter is {@link Optional#empty()} (or, for {@code statuses}, an empty
 * {@link Set}), never {@code null}. {@code statuses} is a set, not a single value, because the
 * public {@code delivery_status} vocabulary this filter serves is many-to-one against the
 * internal {@link DeliveryStatus} states (ADR-003 §1) — filtering by the public value
 * {@code pending} means four internal states, not one.
 */
public record DeliveryPageQuery(
        Optional<Instant> eventCreatedFrom,
        Optional<Instant> eventCreatedTo,
        Set<DeliveryStatus> statuses,
        Optional<String> cursor) {

    public DeliveryPageQuery {
        Objects.requireNonNull(eventCreatedFrom, "eventCreatedFrom must not be null");
        Objects.requireNonNull(eventCreatedTo, "eventCreatedTo must not be null");
        Objects.requireNonNull(statuses, "statuses must not be null (use Set.of())");
        Objects.requireNonNull(cursor, "cursor must not be null");
    }

    public static DeliveryPageQuery unfiltered() {
        return new DeliveryPageQuery(Optional.empty(), Optional.empty(), Set.of(), Optional.empty());
    }
}
