package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.in.pipeline.RegisterNotificationEventUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.NotificationEventRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.subscription.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-002 §1.1 steps 2 and 3, in one transaction: fans an inbound platform event out to its
 * matching subscriptions' {@code deliveries} rows.
 *
 * <p>No network call inside this class (ADR-002 §1.1's closing paragraph, ADR-001 §1): the
 * publish is {@code IngestPublishDispatcher}'s, after the commit.
 *
 * <p>No {@code clientId} comparison anywhere here. Tenant isolation is structural —
 * {@link SubscriptionRepositoryPort#findActiveForEvent} binds {@code client_id} into the query
 * predicate (ADR-003 §2), so a foreign subscription is never returned and there is nothing to
 * compare against.
 */
@Service
public class RegisterNotificationEventUseCaseImpl implements RegisterNotificationEventUseCase {

    private final SubscriptionRepositoryPort subscriptionRepository;
    private final NotificationEventRepositoryPort eventRepository;
    private final DeliveryPipelineRepositoryPort pipelineRepository;
    private final TraceContextPort traceContextPort;
    private final IngestPublishDispatcher publishDispatcher;

    public RegisterNotificationEventUseCaseImpl(
            SubscriptionRepositoryPort subscriptionRepository,
            NotificationEventRepositoryPort eventRepository,
            DeliveryPipelineRepositoryPort pipelineRepository,
            TraceContextPort traceContextPort,
            IngestPublishDispatcher publishDispatcher) {
        this.subscriptionRepository = subscriptionRepository;
        this.eventRepository = eventRepository;
        this.pipelineRepository = pipelineRepository;
        this.traceContextPort = traceContextPort;
        this.publishDispatcher = publishDispatcher;
    }

    @Override
    @Transactional
    public RegisterNotificationEventResult register(RegisterNotificationEventCommand command) {
        List<Subscription> subscriptions =
                subscriptionRepository.findActiveForEvent(command.clientId(), command.eventType());

        NotificationEvent submittedEvent = new NotificationEvent(
                command.eventId(), command.clientId(), command.eventType(), command.content(), command.occurredAt());
        boolean eventInserted = eventRepository.insertIfAbsent(submittedEvent);

        // notification_events is append-only (ADR-003 §3): on a re-ingest, the stored row's
        // clientId/createdAt are authoritative, not this command's, or new delivery rows would
        // disagree with their own parent event (FEAT-005 decision 5).
        NotificationEvent event = eventInserted
                ? submittedEvent
                : eventRepository
                        .findById(command.eventId())
                        .orElseThrow(() -> new IllegalStateException(
                                "notification_events row missing immediately after insertIfAbsent returned false"));

        Optional<String> traceContext = traceContextPort.currentTraceparent();

        List<UUID> deliveryIds = new ArrayList<>(subscriptions.size());
        boolean anyDeliveryInserted = false;

        for (Subscription subscription : subscriptions) {
            Delivery delivery = new Delivery(
                    UUID.randomUUID(),
                    command.eventId(),
                    subscription.subscriptionId(),
                    event.clientId(),
                    DeliveryStatus.PENDING,
                    DeliveryOrigin.INGEST,
                    Optional.empty(),
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    event.createdAt(),
                    traceContext);

            Optional<Delivery> inserted = pipelineRepository.insertIfAbsent(delivery);
            if (inserted.isPresent()) {
                Delivery insertedDelivery = inserted.get();
                anyDeliveryInserted = true;
                deliveryIds.add(insertedDelivery.deliveryId());
                // Only newly inserted rows are published: a replay whose insertIfAbsent
                // returned empty() found a live row the relay is already responsible for,
                // so re-publishing it would be a wasted, duplicate pointer (TASK-005-14).
                publishDispatcher.dispatch(new DeliveryPointer(
                        insertedDelivery.deliveryId(),
                        insertedDelivery.subscriptionId(),
                        0,
                        insertedDelivery.traceContext()));
            } else {
                Delivery live = pipelineRepository
                        .findLiveByEventAndSubscription(command.eventId(), subscription.subscriptionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "no live delivery found for event/subscription pair immediately after a live-pair conflict"));
                deliveryIds.add(live.deliveryId());
            }
        }

        return new RegisterNotificationEventResult(deliveryIds, anyDeliveryInserted);
    }
}
