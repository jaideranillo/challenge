package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.in.selfservice.Accepted;
import com.cobre.challenge.application.port.in.selfservice.Rejected;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryResult;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryUseCase;
import com.cobre.challenge.application.port.in.selfservice.dto.RejectionReason;
import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /notification_events/{id}/replay} (ADR-005 SS1, ADR-007 SS5.5).
 *
 * <p>Fixed order: (1) tenant-scoped resolve on the API pool, empty -&gt;
 * {@code Rejected(TARGET_NOT_FOUND)}; (2) status must be {@code DEAD}, else -&gt;
 * {@code Rejected(TARGET_NOT_DEAD)}; (3) insert the replay row via
 * {@link DeliveryPipelineRepositoryPort#insertReplayIfAbsent(Delivery)} on the pipeline pool,
 * empty -&gt; {@code Rejected(LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS)}, present -&gt;
 * {@code Accepted}. The original {@code DEAD} row is never mutated and nothing is published to a
 * queue; the replay re-enters through the relay's due query.
 *
 * <p>A nonexistent id and another tenant's id are indistinguishable at step 1: both come back as
 * {@link Optional#empty()} from the single tenant-mandatory {@code findById} call, so there is no
 * code path that has ever seen a foreign row (A01/IDOR).
 */
@Service
public class ReplayDeliveryUseCaseImpl implements ReplayDeliveryUseCase {

    private final DeliveryQueryRepositoryPort deliveryQueryRepository;
    private final DeliveryPipelineRepositoryPort pipelineRepository;
    private final TraceContextPort traceContextPort;

    public ReplayDeliveryUseCaseImpl(
            DeliveryQueryRepositoryPort deliveryQueryRepository,
            DeliveryPipelineRepositoryPort pipelineRepository,
            TraceContextPort traceContextPort) {
        this.deliveryQueryRepository = deliveryQueryRepository;
        this.pipelineRepository = pipelineRepository;
        this.traceContextPort = traceContextPort;
    }

    @Override
    public ReplayDeliveryResult replay(ReplayDeliveryCommand command) {
        Optional<Delivery> maybeTarget = resolveTarget(command.deliveryId(), command.tenant());
        if (maybeTarget.isEmpty()) {
            return new Rejected(RejectionReason.TARGET_NOT_FOUND);
        }

        Delivery target = maybeTarget.get();
        if (target.status() != DeliveryStatus.DEAD) {
            return new Rejected(RejectionReason.TARGET_NOT_DEAD);
        }

        Delivery replayRow = new Delivery(
                UUID.randomUUID(),
                target.eventId(),
                target.subscriptionId(),
                target.clientId(),
                DeliveryStatus.PENDING,
                DeliveryOrigin.REPLAY,
                Optional.of(target.deliveryId()),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                target.eventCreatedAt(),
                traceContextPort.currentTraceparent());

        Optional<Delivery> inserted = pipelineRepository.insertReplayIfAbsent(replayRow);
        if (inserted.isEmpty()) {
            return new Rejected(RejectionReason.LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS);
        }

        return new Accepted(inserted.get().deliveryId(), inserted.get().status(), target.traceContext());
    }

    /**
     * Isolated so the API-pool read gets its own transaction (ADR-005 Amendment D1); the pipeline
     * insert runs separately and neither step shares a transaction with the other.
     */
    @Transactional(transactionManager = "apiTransactionManager", readOnly = true)
    protected Optional<Delivery> resolveTarget(UUID deliveryId, TenantId tenant) {
        return deliveryQueryRepository.findById(deliveryId, tenant);
    }
}
