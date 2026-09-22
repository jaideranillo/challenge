package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.queue.dto.PublishBatchResult;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DispatchPendingDeliveriesUseCaseImplTest {

    private static final Instant AS_OF = Instant.parse("2026-09-21T10:00:00Z");

    private final FakePipelinePort pipelinePort = new FakePipelinePort();
    private final FakeSubscriptionPort subscriptionPort = new FakeSubscriptionPort();
    private final FakeQueuePort queuePort = new FakeQueuePort();
    private final FakeTraceContextPort traceContextPort = new FakeTraceContextPort();
    private final RelayBatchClaimer relayBatchClaimer = new RelayBatchClaimer(pipelinePort, subscriptionPort);
    private final DispatchPendingDeliveriesUseCaseImpl useCase = new DispatchPendingDeliveriesUseCaseImpl(
            relayBatchClaimer, queuePort, traceContextPort, new SimpleMeterRegistry());

    @Test
    void emptyClaim_returnsZeroZero_andMakesNoPublishCall() {
        DispatchPendingDeliveriesResult result = useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(0, 0));
        assertThat(queuePort.publishBatchCalls).isZero();
    }

    @Test
    void claimedBatch_publishesAllPointers_andReportsCounts() {
        Delivery delivery = pendingDelivery();
        pipelinePort.claimed = List.of(delivery);
        queuePort.result = new PublishBatchResult(1, List.of());

        DispatchPendingDeliveriesResult result = useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(1, 1));
        assertThat(queuePort.lastPointers).hasSize(1);
        DeliveryPointer pointer = queuePort.lastPointers.get(0);
        assertThat(pointer.deliveryId()).isEqualTo(delivery.deliveryId());
        assertThat(pointer.subscriptionId()).isEqualTo(delivery.subscriptionId());
        assertThat(pointer.attemptHint()).isEqualTo(delivery.attemptCount());
    }

    @Test
    void traceparent_prefersCurrentSpan_overPersistedTraceContext() {
        Delivery delivery = pendingDelivery();
        pipelinePort.claimed = List.of(delivery);
        traceContextPort.current = Optional.of("00-current-01");
        queuePort.result = new PublishBatchResult(1, List.of());

        useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(queuePort.lastPointers.get(0).traceparent()).contains("00-current-01");
    }

    @Test
    void traceparent_fallsBackToPersistedTraceContext_whenNoSpanActive() {
        Delivery delivery = pendingDelivery();
        pipelinePort.claimed = List.of(delivery);
        traceContextPort.current = Optional.empty();
        queuePort.result = new PublishBatchResult(1, List.of());

        useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(queuePort.lastPointers.get(0).traceparent()).isEqualTo(delivery.traceContext());
    }

    @Test
    void failedEntries_triggerNoDatabaseWrite_andAreCountedInResult() {
        Delivery delivery = pendingDelivery();
        pipelinePort.claimed = List.of(delivery);
        queuePort.result = new PublishBatchResult(0, List.of(delivery.deliveryId()));

        DispatchPendingDeliveriesResult result = useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(1, 0));
        assertThat(pipelinePort.writeCalls).isZero();
    }

    @Test
    void thrownPublish_isCaughtAndReported_notPropagated() {
        Delivery delivery = pendingDelivery();
        pipelinePort.claimed = List.of(delivery);
        queuePort.throwOnPublish = true;

        DispatchPendingDeliveriesResult result = useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(1, 0));
    }

    @Test
    void thrownClaim_propagates() {
        pipelinePort.throwOnClaim = true;

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> useCase.dispatch(new DispatchPendingDeliveriesCommand(10, AS_OF)));
    }

    private static Delivery pendingDelivery() {
        return new Delivery(
                UUID.randomUUID(),
                "evt-1",
                UUID.randomUUID(),
                "client-1",
                DeliveryStatus.QUEUED,
                DeliveryOrigin.INGEST,
                Optional.empty(),
                0,
                Optional.of(AS_OF.plusSeconds(300)),
                Optional.empty(),
                Optional.empty(),
                AS_OF,
                Optional.of("00-persisted-01"));
    }

    private static class FakePipelinePort implements DeliveryPipelineRepositoryPort {
        List<Delivery> claimed = List.of();
        boolean throwOnClaim;
        int writeCalls;

        @Override
        public List<Delivery> claimDue(int batchLimit, Instant asOf) {
            if (throwOnClaim) {
                throw new IllegalStateException("claim failed");
            }
            return claimed;
        }

        @Override
        public Delivery insert(Delivery delivery) {
            writeCalls++;
            return delivery;
        }

        @Override
        public Optional<Delivery> insertIfAbsent(Delivery delivery) {
            writeCalls++;
            return Optional.of(delivery);
        }

        @Override
        public Optional<Delivery> insertReplayIfAbsent(Delivery delivery) {
            writeCalls++;
            return Optional.of(delivery);
        }

        @Override
        public Optional<Delivery> findById(UUID deliveryId) {
            return Optional.empty();
        }

        @Override
        public Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId) {
            return Optional.empty();
        }

        @Override
        public boolean claimForProcessing(UUID deliveryId, Instant now) {
            writeCalls++;
            return true;
        }

        @Override
        public boolean markDelivered(UUID deliveryId, Instant deliveredAt) {
            writeCalls++;
            return true;
        }

        @Override
        public boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now) {
            writeCalls++;
            return true;
        }

        @Override
        public boolean markDead(UUID deliveryId, String lastError, Instant now) {
            writeCalls++;
            return true;
        }

        @Override
        public boolean markFailed(UUID deliveryId, String lastError, Instant now) {
            writeCalls++;
            return true;
        }

        @Override
        public boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt) {
            writeCalls++;
            return true;
        }
    }

    private static class FakeSubscriptionPort implements SubscriptionRepositoryPort {
        @Override
        public boolean promoteToHalfOpen(UUID subscriptionId, Instant asOf) {
            return false;
        }

        @Override
        public List<com.cobre.challenge.domain.model.subscription.Subscription> findActiveForEvent(
                String clientId, String eventType) {
            return List.of();
        }

        @Override
        public Optional<com.cobre.challenge.domain.model.subscription.Subscription> findById(UUID subscriptionId) {
            return Optional.empty();
        }

        @Override
        public boolean deactivate(UUID subscriptionId) {
            return true;
        }

        @Override
        public boolean setThrottledUntil(UUID subscriptionId, Instant throttledUntil) {
            return true;
        }

        @Override
        public boolean tripCircuit(UUID subscriptionId, java.time.Duration baseCooldown, java.time.Duration maxCooldown, Instant now) {
            return true;
        }

        @Override
        public boolean reopenCircuit(UUID subscriptionId, java.time.Duration baseCooldown, java.time.Duration maxCooldown, Instant now) {
            return true;
        }

        @Override
        public boolean closeCircuit(UUID subscriptionId, Instant now) {
            return true;
        }
    }

    private static class FakeQueuePort implements NotificationQueuePort {
        List<DeliveryPointer> lastPointers = List.of();
        int publishBatchCalls;
        PublishBatchResult result = new PublishBatchResult(0, List.of());
        boolean throwOnPublish;

        @Override
        public void publish(DeliveryPointer pointer) {}

        @Override
        public PublishBatchResult publishBatch(List<DeliveryPointer> pointers) {
            publishBatchCalls++;
            lastPointers = new ArrayList<>(pointers);
            if (throwOnPublish) {
                throw new RuntimeException("queue unreachable");
            }
            return result;
        }
    }

    private static class FakeTraceContextPort implements TraceContextPort {
        Optional<String> current = Optional.empty();

        @Override
        public Optional<String> currentTraceparent() {
            return current;
        }
    }
}
