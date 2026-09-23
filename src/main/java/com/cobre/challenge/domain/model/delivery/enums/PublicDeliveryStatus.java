package com.cobre.challenge.domain.model.delivery.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The public {@code delivery_status} vocabulary (ADR-003 §1's mapping table), the only status
 * shape ever exposed on the wire — {@link DeliveryStatus}, the 7-state internal enum, never
 * leaves this service (ADR-003 §1.1). A many-to-one mapping in both directions: several internal
 * states can share one public value, so filtering by {@link #internalStates()} needs a
 * {@code Set}, never a single value, and rendering an internal state back out needs {@link #of}.
 */
public enum PublicDeliveryStatus {
    PENDING("pending", EnumSet.of(
            DeliveryStatus.PENDING, DeliveryStatus.QUEUED, DeliveryStatus.PROCESSING, DeliveryStatus.RETRYING)),
    COMPLETED("completed", EnumSet.of(DeliveryStatus.DELIVERED)),
    FAILED("failed", EnumSet.of(DeliveryStatus.DEAD, DeliveryStatus.FAILED));

    private final String wireName;
    private final Set<DeliveryStatus> internalStates;

    PublicDeliveryStatus(String wireName, Set<DeliveryStatus> internalStates) {
        this.wireName = wireName;
        this.internalStates = internalStates;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The internal states this public value expands to when used as a filter. Never empty. */
    public Set<DeliveryStatus> internalStates() {
        return internalStates;
    }

    /**
     * Parses a wire value case-insensitively. {@link Optional#empty()} for anything that doesn't
     * match — never throws, so the caller decides the failure response (a clean 400, not letting
     * Spring's enum-binding machinery throw and surface as a stray 403 via the terminal
     * {@code denyAll()} chain on the servlet container's internal {@code /error} forward).
     */
    public static Optional<PublicDeliveryStatus> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (PublicDeliveryStatus value : values()) {
            if (value.wireName.equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** The public value an internal state renders as, for API responses. */
    public static PublicDeliveryStatus of(DeliveryStatus internal) {
        for (PublicDeliveryStatus value : values()) {
            if (value.internalStates.contains(internal)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No public status maps internal state " + internal);
    }
}
