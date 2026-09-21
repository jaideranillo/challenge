package com.cobre.challenge.application.port.out.queue.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A flat envelope of fixed arity, exactly four scalar fields (ADR-004 SS1) — no nested
 * document, no event content, no target URL, no secret. This flatness is a stated design
 * property and the record must not grow a fifth structured component.
 */
public record DeliveryPointer(UUID deliveryId, UUID subscriptionId, int attemptHint, Optional<String> traceparent) {

    public DeliveryPointer {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(traceparent, "traceparent must not be null (use Optional.empty())");
    }
}
