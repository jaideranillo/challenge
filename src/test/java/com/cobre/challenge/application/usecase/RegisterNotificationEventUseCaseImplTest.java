package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.NotificationEventRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.queue.dto.PublishBatchResult;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.subscription.Subscription;
import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RegisterNotificationEventUseCaseImplTest {

    private static final Instant COMMAND_OCCURRED_AT = Instant.parse("2026-09-20T10:00:00Z");

    private final FakeSubscriptionRepository subscriptionRepository = new FakeSubscriptionRepository();
    private final FakeNotificationEventRepository eventRepository = new FakeNotificationEventRepository();
    private final FakeDeliveryPipelineRepository pipelineRepository = new FakeDeliveryPipelineRepository();
    private final FakeTraceContextPort traceContextPort = new FakeTraceContextPort();
    private final IngestPublishDispatcher publishDispatcher =
            new IngestPublishDispatcher(new NoOpQueuePort(), new SimpleMeterRegistry());

    private final RegisterNotificationEventUseCaseImpl useCase = new RegisterNotificationEventUseCaseImpl(
            subscriptionRepository, eventRepository, pipelineRepository, traceContextPort, publishDispatcher);

    // dispatch() registers a transaction synchronization (TASK-005-14); this use-case test is
    // plain JUnit with no real transaction, so a synchronization context is opened manually.
    @BeforeEach
    void openTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void fansOutToEverySubscriptionMatchingTheEvent() {
        Subscription s1 = subscription("sub-1", "client-1", "payment.created");
        Subscription s2 = subscription("sub-2", "client-1", "payment.created");
        subscriptionRepository.activeFor("client-1", "payment.created", List.of(s1, s2));

        RegisterNotificationEventResult result = useCase.register(command("EVT001", "client-1"));

        assertThat(result.deliveryIds()).hasSize(2);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(pipelineRepository.insertedDeliveries).hasSize(2);
        assertThat(eventRepository.stored).containsKey("EVT001");
    }

    @Test
    void zeroMatchingSubscriptionsIsASuccessWithAnEmptyDeliveryIdsList() {
        subscriptionRepository.activeFor("client-1", "payment.created", List.of());

        RegisterNotificationEventResult result = useCase.register(command("EVT001", "client-1"));

        assertThat(result.deliveryIds()).isEmpty();
        assertThat(result.newlyCreated()).isFalse();
        assertThat(eventRepository.stored).containsKey("EVT001");
    }

    @Test
    void replayingAnAlreadyIngestedEventReturnsExistingDeliveryIdsAndNewlyCreatedFalse() {
        Subscription s1 = subscription("sub-1", "client-1", "payment.created");
        subscriptionRepository.activeFor("client-1", "payment.created", List.of(s1));

        RegisterNotificationEventResult first = useCase.register(command("EVT001", "client-1"));
        RegisterNotificationEventResult second = useCase.register(command("EVT001", "client-1"));

        assertThat(second.deliveryIds()).isEqualTo(first.deliveryIds());
        assertThat(first.newlyCreated()).isTrue();
        assertThat(second.newlyCreated()).isFalse();
        assertThat(pipelineRepository.insertedDeliveries).hasSize(1);
    }

    @Test
    void reingestWithADifferentOccurredAtStillUsesTheStoredEventCreatedAt() {
        Subscription s1 = subscription("sub-1", "client-1", "payment.created");
        subscriptionRepository.activeFor("client-1", "payment.created", List.of(s1));

        useCase.register(command("EVT001", "client-1"));

        RegisterNotificationEventCommand reingest = new RegisterNotificationEventCommand(
                "EVT001", "client-1", "payment.created", "different-content", COMMAND_OCCURRED_AT.plusSeconds(3600));
        useCase.register(reingest);

        assertThat(pipelineRepository.insertedDeliveries).hasSize(1);
        assertThat(pipelineRepository.insertedDeliveries.get(0).eventCreatedAt()).isEqualTo(COMMAND_OCCURRED_AT);
        assertThat(eventRepository.stored.get("EVT001").content()).isEqualTo("content");
    }

    private static RegisterNotificationEventCommand command(String eventId, String clientId) {
        return new RegisterNotificationEventCommand(
                eventId, clientId, "payment.created", "content", COMMAND_OCCURRED_AT);
    }

    private static Subscription subscription(String id, String clientId, String eventType) {
        return new Subscription(
                UUID.nameUUIDFromBytes(id.getBytes()),
                clientId,
                "https://example.com/webhook",
                "secret-ref",
                Optional.empty(),
                Optional.empty(),
                Set.of(eventType),
                true,
                VerificationState.VERIFIED,
                10,
                CircuitState.CLOSED,
                Optional.empty());
    }

    private static class FakeSubscriptionRepository implements SubscriptionRepositoryPort {
        private final Map<String, List<Subscription>> byClientAndType = new HashMap<>();

        void activeFor(String clientId, String eventType, List<Subscription> subscriptions) {
            byClientAndType.put(clientId + ":" + eventType, subscriptions);
        }

        @Override
        public List<Subscription> findActiveForEvent(String clientId, String eventType) {
            return byClientAndType.getOrDefault(clientId + ":" + eventType, List.of());
        }

        @Override
        public Optional<Subscription> findById(UUID subscriptionId) {
            return Optional.empty();
        }

        @Override
        public boolean deactivate(UUID subscriptionId) {
            return false;
        }

        @Override
        public boolean setThrottledUntil(UUID subscriptionId, Instant throttledUntil) {
            return false;
        }

        @Override
        public boolean tripCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now) {
            return false;
        }

        @Override
        public boolean reopenCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now) {
            return false;
        }

        @Override
        public boolean promoteToHalfOpen(UUID subscriptionId, Instant asOf) {
            return false;
        }

        @Override
        public boolean closeCircuit(UUID subscriptionId, Instant now) {
            return false;
        }
    }

    private static class FakeNotificationEventRepository implements NotificationEventRepositoryPort {
        private final Map<String, NotificationEvent> stored = new HashMap<>();

        @Override
        public boolean insertIfAbsent(NotificationEvent event) {
            return stored.putIfAbsent(event.eventId(), event) == null;
        }

        @Override
        public Optional<NotificationEvent> findById(String eventId) {
            return Optional.ofNullable(stored.get(eventId));
        }
    }

    private static class FakeDeliveryPipelineRepository implements DeliveryPipelineRepositoryPort {
        private final List<Delivery> insertedDeliveries = new ArrayList<>();

        @Override
        public Delivery insert(Delivery delivery) {
            insertedDeliveries.add(delivery);
            return delivery;
        }

        @Override
        public Optional<Delivery> insertIfAbsent(Delivery delivery) {
            boolean liveExists = insertedDeliveries.stream()
                    .anyMatch(existing -> existing.eventId().equals(delivery.eventId())
                            && existing.subscriptionId().equals(delivery.subscriptionId()));
            if (liveExists) {
                return Optional.empty();
            }
            insertedDeliveries.add(delivery);
            return Optional.of(delivery);
        }

        @Override
        public Optional<Delivery> findById(UUID deliveryId) {
            return insertedDeliveries.stream()
                    .filter(d -> d.deliveryId().equals(deliveryId))
                    .findFirst();
        }

        @Override
        public Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId) {
            return insertedDeliveries.stream()
                    .filter(d -> d.eventId().equals(eventId) && d.subscriptionId().equals(subscriptionId))
                    .findFirst();
        }

        @Override
        public boolean claimForProcessing(UUID deliveryId, Instant now) {
            return false;
        }

        @Override
        public boolean markDelivered(UUID deliveryId, Instant deliveredAt) {
            return false;
        }

        @Override
        public boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now) {
            return false;
        }

        @Override
        public boolean markDead(UUID deliveryId, String lastError, Instant now) {
            return false;
        }

        @Override
        public boolean markFailed(UUID deliveryId, String lastError, Instant now) {
            return false;
        }

        @Override
        public boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt) {
            return false;
        }

        @Override
        public List<Delivery> claimDue(int batchLimit, Instant asOf) {
            return List.of();
        }
    }

    private static class FakeTraceContextPort implements TraceContextPort {
        @Override
        public Optional<String> currentTraceparent() {
            return Optional.of("00-trace-01");
        }
    }

    private static class NoOpQueuePort implements NotificationQueuePort {
        @Override
        public void publish(DeliveryPointer pointer) {
            // no-op: this test exercises the use case, not the publish path (TASK-005-14).
        }

        @Override
        public PublishBatchResult publishBatch(List<DeliveryPointer> pointers) {
            return new PublishBatchResult(pointers.size(), List.of());
        }
    }
}
