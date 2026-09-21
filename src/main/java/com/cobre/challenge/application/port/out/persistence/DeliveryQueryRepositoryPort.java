package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence contract for client-facing delivery queries (ADR-005 §1, ADR-007 §5.2).
 *
 * <p><strong>Tenant mandatory on every method of this interface.</strong> Every method takes a
 * {@code clientId} parameter and must use it as a predicate, never as a post-hoc filter
 * (ADR-003 §2, ADR-007 §5.2). There is no unscoped overload on this interface, ever.
 * The reason the pipeline port's {@link DeliveryPipelineRepositoryPort#findById(UUID)} is safe
 * without a tenant is that it is not reachable from here — that distinction is enforced by the
 * type split (ADR-007 Amendment E1).
 */
public interface DeliveryQueryRepositoryPort {

    /**
     * Tenant-scoped delivery lookup.
     *
     * <p>{@code clientId} is a query predicate, not a post-hoc filter. Returns
     * {@link Optional#empty()} when no row matches or when the row belongs to a different tenant
     * (ADR-007 §5.2, A01/IDOR).
     */
    Optional<Delivery> findById(UUID deliveryId, String clientId);

    /**
     * Keyset-paginated delivery list for the self-service API (ADR-005 §1).
     *
     * <p>{@code clientId} is mandatory and is a query predicate. {@code eventCreatedFrom} and
     * {@code eventCreatedTo} both bound {@code deliveries.event_created_at} (ADR-005 Amendment D2),
     * not the row's own {@code created_at}, so a replay or recovered delivery is found in the
     * window of the original event. The keyset tuple is {@code (event_created_at, delivery_id)}.
     *
     * @param clientId tenant identifier; mandatory
     * @param query    filters: {@code eventCreatedFrom}/{@code eventCreatedTo} bound
     *                 {@code event_created_at} (inclusive/exclusive respectively),
     *                 {@code status} is an optional status filter, {@code cursor} is an
     *                 opaque keyset cursor from a prior page's {@code nextCursor}
     * @param limit    maximum number of results to return
     */
    DeliveryPage findPage(String clientId, DeliveryPageQuery query, int limit);
}
