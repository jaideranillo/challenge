package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.List;
import java.util.UUID;

/**
 * Outbound persistence contract for the client-facing attempt history behind
 * {@code GET /notification_events/{id}} (ADR-005 §1).
 *
 * <p>Split from {@link DeliveryAttemptRepositoryPort} rather than adding a tenant-scoped overload
 * there: that port is injected into pipeline components and must stay cross-tenant (ADR-007 §5.2,
 * Amendment E1); this one is tenant-mandatory (ISP — two consumers, two ports).
 *
 * <p>{@code delivery_attempts} has no {@code client_id} column of its own — {@code tenant} is
 * expressed as a predicate on the parent {@code deliveries} row, both in the adapter's SQL
 * (TASK-008-16) and in the RLS policy (TASK-008-08).
 *
 * <p><strong>Tenant mandatory.</strong> A foreign delivery's attempts and a nonexistent delivery's
 * attempts are indistinguishable through this port: both return an empty {@link List}, never
 * {@code null} — the use case must not be able to tell them apart (ADR-007 §5.5, A01/IDOR).
 */
public interface DeliveryAttemptQueryRepositoryPort {

    /** Ordered by attempt number; empty list when none, or when {@code deliveryId} belongs to a different tenant. */
    List<DeliveryAttempt> findByDeliveryId(UUID deliveryId, TenantId tenant);
}
