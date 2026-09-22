package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchedDeliveryEntry;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.queue.dto.PublishBatchResult;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.application.usecase.dto.RelayBatchClaimResult;
import com.cobre.challenge.domain.model.delivery.Delivery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One relay cycle (ADR-002 SS2.1): claim commits first, publish happens strictly after (ADR-001 SS1). */
@Service
public class DispatchPendingDeliveriesUseCaseImpl implements DispatchPendingDeliveriesUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchPendingDeliveriesUseCaseImpl.class);

    private final RelayBatchClaimer relayBatchClaimer;
    private final NotificationQueuePort queuePort;
    private final TraceContextPort traceContextPort;
    private final Counter claimedCounter;
    private final Counter publishedCounter;
    private final Counter publishFailedCounter;
    private final Counter circuitPromotedCounter;
    private final Timer dispatchLatencyTimer;
    private final MeterRegistry meterRegistry;

    public DispatchPendingDeliveriesUseCaseImpl(
            RelayBatchClaimer relayBatchClaimer,
            NotificationQueuePort queuePort,
            TraceContextPort traceContextPort,
            MeterRegistry meterRegistry) {
        this.relayBatchClaimer = relayBatchClaimer;
        this.queuePort = queuePort;
        this.traceContextPort = traceContextPort;
        this.meterRegistry = meterRegistry;
        // No client_id/subscription_id tag on any of these (ADR-002 Q8, SS3.1).
        this.claimedCounter = meterRegistry.counter("notification.relay.claimed");
        this.publishedCounter = meterRegistry.counter("notification.relay.published");
        this.publishFailedCounter = meterRegistry.counter("notification.relay.publish.failed");
        this.circuitPromotedCounter =
                meterRegistry.counter("notification.circuit.transition", "direction", "OPEN_TO_HALF_OPEN");
        // Untagged (ADR-008 §4.1/§4.3): one poll cycle, claim query through the last publish returning.
        this.dispatchLatencyTimer = meterRegistry.timer("notification.relay.dispatch.latency");
    }

    @Override
    public DispatchPendingDeliveriesResult dispatch(DispatchPendingDeliveriesCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doDispatch(command);
        } finally {
            sample.stop(dispatchLatencyTimer);
        }
    }

    private DispatchPendingDeliveriesResult doDispatch(DispatchPendingDeliveriesCommand command) {
        // Transaction boundary is RelayBatchClaimer's alone; it commits before this method returns from the call.
        RelayBatchClaimResult claim = relayBatchClaimer.claimAndPromote(command.batchLimit(), command.asOf());
        List<Delivery> claimed = claim.claimed();

        claimedCounter.increment(claimed.size());
        if (claim.promotedCount() > 0) {
            circuitPromotedCounter.increment(claim.promotedCount());
        }

        if (claimed.isEmpty()) {
            return new DispatchPendingDeliveriesResult(0, 0, List.of());
        }

        Optional<String> currentTraceparent = traceContextPort.currentTraceparent();
        List<DeliveryPointer> pointers = new ArrayList<>(claimed.size());
        for (Delivery delivery : claimed) {
            pointers.add(new DeliveryPointer(
                    delivery.deliveryId(), delivery.subscriptionId(), delivery.attemptCount(),
                    delivery.traceContext().or(() -> currentTraceparent)));
        }

        PublishOutcome outcome = publishBatch(pointers, claimed.size());
        List<DispatchedDeliveryEntry> entries = new ArrayList<>(pointers.size());
        for (DeliveryPointer pointer : pointers) {
            entries.add(new DispatchedDeliveryEntry(
                    pointer.deliveryId(),
                    pointer.traceparent(),
                    !outcome.failedDeliveryIds().contains(pointer.deliveryId())));
        }

        log.info("Relay cycle claimed_count={} published_count={}", claimed.size(), outcome.publishedCount());
        return new DispatchPendingDeliveriesResult(claimed.size(), outcome.publishedCount(), entries);
    }

    // Failed/thrown publishes trigger no database write: the pushed next_attempt_at is the recovery mechanism (ADR-002 SS2.1).
    private PublishOutcome publishBatch(List<DeliveryPointer> pointers, int claimedCount) {
        try {
            PublishBatchResult result = queuePort.publishBatch(pointers);
            List<UUID> failedDeliveryIds = result.failedDeliveryIds();
            if (!failedDeliveryIds.isEmpty()) {
                publishFailedCounter.increment(failedDeliveryIds.size());
                for (UUID deliveryId : failedDeliveryIds) {
                    log.warn("Publish failed for delivery_id={}", deliveryId);
                }
            }
            if (result.publishedCount() > 0) {
                publishedCounter.increment(result.publishedCount());
            }
            return new PublishOutcome(result.publishedCount(), Set.copyOf(failedDeliveryIds));
        } catch (Throwable t) {
            publishFailedCounter.increment(claimedCount);
            log.warn("publishBatch threw for claimed_count={}", claimedCount, t);
            Set<UUID> allFailed = new HashSet<>(pointers.size());
            for (DeliveryPointer pointer : pointers) {
                allFailed.add(pointer.deliveryId());
            }
            return new PublishOutcome(0, allFailed);
        }
    }

    private record PublishOutcome(int publishedCount, Set<UUID> failedDeliveryIds) {
    }
}
