package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence contract for {@code deliveries} (ADR-005 SS1). Kept to
 * what the current use cases need; no speculative finders and no blanket
 * upsert (that would bypass the state-guarded transition ADR-003 SS1.1
 * relies on for safety).
 */
public interface DeliveryRepositoryPort {

    /** Inserts a new delivery row (ingest, replay and recovery all use this one shape). */
    Delivery insert(Delivery delivery);

    /**
     * State-guarded status transition: {@code UPDATE ... WHERE delivery_id = ? AND status = expected}.
     * Zero rows affected is a normal, expected outcome (ADR-002 SS2.2 step 2), never an exception.
     *
     * @return true when exactly one row was transitioned
     */
    boolean transitionStatus(UUID deliveryId, DeliveryStatus expected, DeliveryStatus target, Instant now);

    /** Claims due deliveries for dispatch, bounded by {@code batchLimit} and as of {@code asOf}. */
    List<Delivery> claimDue(int batchLimit, Instant asOf);

    /** Tenant-scoped read; there is no unscoped overload (A01/IDOR, ADR-003 SS2). */
    Optional<Delivery> findById(UUID deliveryId, String clientId);

    /** Keyset page read for the list endpoint. */
    DeliveryPage findPage(
            String clientId,
            Optional<Instant> createdFrom,
            Optional<Instant> createdTo,
            Optional<DeliveryStatus> status,
            Optional<String> cursor,
            int limit);
}
