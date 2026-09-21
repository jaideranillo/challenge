package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import java.util.List;
import java.util.UUID;

/**
 * Outbound persistence contract for the append-only {@code delivery_attempts}
 * table (ADR-003 SS3). No update, no delete: the table is append-only by
 * design and this port must not offer a way to violate that.
 */
public interface DeliveryAttemptRepositoryPort {

    DeliveryAttempt insert(DeliveryAttempt attempt);

    /** Ordered by attempt number; empty list when none. */
    List<DeliveryAttempt> findByDeliveryId(UUID deliveryId);
}
