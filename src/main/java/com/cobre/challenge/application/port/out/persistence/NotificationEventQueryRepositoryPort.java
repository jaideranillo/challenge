package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.Optional;

/**
 * Outbound persistence contract for the client-facing event read behind
 * {@code GET /notification_events/{id}} (ADR-005 §1).
 *
 * <p>Split from {@link NotificationEventRepositoryPort} rather than adding a tenant-scoped
 * overload there: that port is injected into pipeline components and must stay cross-tenant
 * (ADR-007 §5.2, Amendment E1); this one is tenant-mandatory (ISP — two consumers, two ports).
 *
 * <p><strong>Tenant mandatory.</strong> {@code tenant} is a query predicate. A foreign row and a
 * nonexistent row are indistinguishable through this port: both return {@link Optional#empty()},
 * never {@code null}. The port offers no way to tell them apart because the use case must not be
 * able to, which is what ADR-007 §5.5's 404-on-cross-tenant behavior depends on (A01/IDOR).
 */
public interface NotificationEventQueryRepositoryPort {

    /**
     * @param eventId the platform's own event id ({@code notification_events.event_id}, {@code text},
     *     e.g. {@code EVT001}) — a {@code String} business key, taken from {@code Delivery.eventId()},
     *     not a UUID
     * @return the event, or {@link Optional#empty()} when no row exists for {@code eventId} or
     *     the row belongs to a different tenant — never {@code null}
     */
    Optional<NotificationEvent> findById(String eventId, TenantId tenant);
}
