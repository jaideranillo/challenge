package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.in.selfservice.Accepted;
import com.cobre.challenge.application.port.in.selfservice.Rejected;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryResult;
import com.cobre.challenge.application.port.in.selfservice.dto.RejectionReason;
import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayDeliveryUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final TenantId TENANT = new TenantId("client-1");
    private static final TenantId OTHER_TENANT = new TenantId("client-2");
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();

    private final FakeDeliveryQueryRepository deliveryQueryRepository = new FakeDeliveryQueryRepository();
    private final FakePipelineRepository pipelineRepository = new FakePipelineRepository();
    private final FakeTraceContextPort traceContextPort = new FakeTraceContextPort();

    private final ReplayDeliveryUseCaseImpl useCase =
            new ReplayDeliveryUseCaseImpl(deliveryQueryRepository, pipelineRepository, traceContextPort);

    @Test
    void deadTargetInsertsAReplayRowAndReturnsAccepted() {
        UUID originalId = UUID.randomUUID();
        Delivery original = deliveryWithStatus(originalId, TENANT, DeliveryStatus.DEAD);
        deliveryQueryRepository.put(originalId, TENANT, original);
        traceContextPort.current = Optional.of("00-replay-request-01");

        ReplayDeliveryResult result = useCase.replay(new ReplayDeliveryCommand(originalId, TENANT, "idem-1"));

        assertThat(result).isInstanceOf(Accepted.class);
        Accepted accepted = (Accepted) result;
        assertThat(accepted.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(accepted.newDeliveryId()).isNotEqualTo(originalId);
        // The original DEAD row's own trace_context (empty for deliveryWithStatus's default) is
        // carried through for the adapter's notification.replay span link (ADR-008 §2.5).
        assertThat(accepted.originalTraceContext()).isEqualTo(original.traceContext());

        Delivery inserted = pipelineRepository.lastInsertReplayArg;
        assertThat(inserted.deliveryId()).isEqualTo(accepted.newDeliveryId());
        assertThat(inserted.eventId()).isEqualTo(original.eventId());
        assertThat(inserted.subscriptionId()).isEqualTo(original.subscriptionId());
        assertThat(inserted.clientId()).isEqualTo(original.clientId());
        assertThat(inserted.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(inserted.attemptCount()).isZero();
        assertThat(inserted.origin()).isEqualTo(DeliveryOrigin.REPLAY);
        assertThat(inserted.replayedFrom()).contains(originalId);
        assertThat(inserted.eventCreatedAt()).isEqualTo(original.eventCreatedAt());
        // The replay row's own trace_context is the replay request's current traceparent, not
        // Optional.empty() and not the original row's (ADR-008 §2.5) - a fresh business flow.
        assertThat(inserted.traceContext()).contains("00-replay-request-01");

        // Only the one insert call was made - the original DEAD row was never written to.
        assertThat(pipelineRepository.callCount).isEqualTo(1);
    }

    @Test
    void liveOrDeliveredConflictOnInsertReturnsRejected() {
        pipelineRepository.rejectInsertReplay = true;
        UUID originalId = UUID.randomUUID();
        deliveryQueryRepository.put(originalId, TENANT, deliveryWithStatus(originalId, TENANT, DeliveryStatus.DEAD));

        ReplayDeliveryResult result = useCase.replay(new ReplayDeliveryCommand(originalId, TENANT, "idem-1"));

        assertThat(result).isEqualTo(new Rejected(RejectionReason.LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS));
    }

    @Test
    void nonexistentIdAndForeignTenantIdProduceTheIdenticalRejectionAndNeverCallThePipelinePort() {
        UUID nonexistentId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        deliveryQueryRepository.put(
                foreignId, OTHER_TENANT, deliveryWithStatus(foreignId, OTHER_TENANT, DeliveryStatus.DEAD));

        ReplayDeliveryResult caseANonexistentId =
                useCase.replay(new ReplayDeliveryCommand(nonexistentId, TENANT, "idem-1"));
        ReplayDeliveryResult caseBForeignTenantId =
                useCase.replay(new ReplayDeliveryCommand(foreignId, TENANT, "idem-2"));

        assertThat(caseANonexistentId)
                .as("a nonexistent id and another tenant's id must produce the identical result")
                .isEqualTo(caseBForeignTenantId);
        assertThat(caseANonexistentId).isEqualTo(new Rejected(RejectionReason.TARGET_NOT_FOUND));
        assertThat(pipelineRepository.callCount)
                .as("the pipeline port must not be reached for either not-found case")
                .isZero();
    }

    @Test
    void everyNonDeadStatusIsRejectedAsTargetNotDead() {
        for (DeliveryStatus status : DeliveryStatus.values()) {
            if (status == DeliveryStatus.DEAD) {
                continue;
            }

            UUID id = UUID.randomUUID();
            FakeDeliveryQueryRepository queryRepository = new FakeDeliveryQueryRepository();
            queryRepository.put(id, TENANT, deliveryWithStatus(id, TENANT, status));
            FakePipelineRepository pipeline = new FakePipelineRepository();
            ReplayDeliveryUseCaseImpl useCaseUnderTest =
                    new ReplayDeliveryUseCaseImpl(queryRepository, pipeline, traceContextPort);

            ReplayDeliveryResult result = useCaseUnderTest.replay(new ReplayDeliveryCommand(id, TENANT, "idem"));

            assertThat(result).as("status %s", status).isEqualTo(new Rejected(RejectionReason.TARGET_NOT_DEAD));
            assertThat(pipeline.callCount).as("status %s", status).isZero();
        }
    }

    private static Delivery deliveryWithStatus(UUID deliveryId, TenantId tenant, DeliveryStatus status) {
        Optional<Instant> deliveredAt = status == DeliveryStatus.DELIVERED ? Optional.of(NOW) : Optional.empty();
        return new Delivery(
                deliveryId,
                "EVT001",
                SUBSCRIPTION_ID,
                tenant.value(),
                status,
                DeliveryOrigin.INGEST,
                Optional.empty(),
                1,
                Optional.empty(),
                Optional.empty(),
                deliveredAt,
                NOW,
                Optional.empty());
    }

    private static class FakeDeliveryQueryRepository implements DeliveryQueryRepositoryPort {
        private final Map<TenantId, Map<UUID, Delivery>> byTenant = new HashMap<>();

        void put(UUID deliveryId, TenantId tenant, Delivery delivery) {
            byTenant.computeIfAbsent(tenant, t -> new HashMap<>()).put(deliveryId, delivery);
        }

        @Override
        public Optional<Delivery> findById(UUID deliveryId, TenantId tenant) {
            return Optional.ofNullable(byTenant.getOrDefault(tenant, Map.of()).get(deliveryId));
        }

        @Override
        public DeliveryPage findPage(TenantId tenant, DeliveryPageQuery query, int limit) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static class FakeTraceContextPort implements TraceContextPort {
        Optional<String> current = Optional.empty();

        @Override
        public Optional<String> currentTraceparent() {
            return current;
        }
    }

    private static class FakePipelineRepository implements DeliveryPipelineRepositoryPort {
        private int callCount;
        private Delivery lastInsertReplayArg;
        private boolean rejectInsertReplay;

        private void track() {
            callCount++;
        }

        @Override
        public Delivery insert(Delivery delivery) {
            track();
            throw new UnsupportedOperationException("not used by this use case");
        }

        @Override
        public Optional<Delivery> insertIfAbsent(Delivery delivery) {
            track();
            throw new UnsupportedOperationException("ingest semantics - must not be called by replay");
        }

        @Override
        public Optional<Delivery> insertReplayIfAbsent(Delivery delivery) {
            track();
            lastInsertReplayArg = delivery;
            return rejectInsertReplay ? Optional.empty() : Optional.of(delivery);
        }

        @Override
        public Optional<Delivery> findById(UUID deliveryId) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean claimForProcessing(UUID deliveryId, Instant now) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean markDelivered(UUID deliveryId, Instant deliveredAt) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean markDead(UUID deliveryId, String lastError, Instant now) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean markFailed(UUID deliveryId, String lastError, Instant now) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<Delivery> claimDue(int batchLimit, Instant asOf) {
            track();
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
