package com.cobre.challenge.application.port.out.persistence;

import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.util.Optional;

/**
 * Outbound persistence contract for {@code notification_events} (ADR-003 Amendment A5).
 *
 * <p>This port is cross-tenant by design: neither method carries a {@code clientId} parameter.
 * The gateway holds the producer's IAM principal, not a client principal, so no tenant scoping
 * is possible or correct here. This is the same carve-out ADR-007 §5.2 grants to
 * {@link DeliveryPipelineRepositoryPort}; it must not later be "fixed" by adding one.
 */
public interface NotificationEventRepositoryPort {

    /**
     * Inserts the event row if, and only if, no row with this {@code eventId} exists yet.
     *
     * <p>{@code notification_events} is append-only and immutable after insert (ADR-003 §3;
     * {@code V1}'s table comment: "Never updated. No updated_at by design") — this method
     * never updates, which is why it is named {@code insertIfAbsent} and not {@code upsert}.
     *
     * <p>This is ADR-002 §1.1 step 3's {@code ON CONFLICT (event_id) DO NOTHING}, using the
     * primary key on {@code notification_events.event_id} ({@code V1}) as the conflict target.
     * Returns {@code true} only when this call inserted the row; {@code false} means the event
     * was already stored. {@code false} is a normal outcome, never an exception, the same
     * convention every conditional write on {@link DeliveryPipelineRepositoryPort} follows.
     *
     * @return {@code true} when this call inserted the row, {@code false} when it already existed
     */
    boolean insertIfAbsent(NotificationEvent event);

    /**
     * Loads the event by its id, because the stored {@code createdAt} is authoritative.
     *
     * <p>On a re-ingest whose command carries a different {@code occurredAt}, the use case must
     * use the stored value, or new delivery rows would disagree with their parent event about
     * when the event happened (FEAT-005 decision 5).
     *
     * @return the event, or {@link Optional#empty()} when no row exists for {@code eventId} —
     *     never {@code null}
     */
    Optional<NotificationEvent> findById(String eventId);
}
