package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.usecase.dto.RelayBatchClaimResult;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Claim transaction (ADR-002 §2.1, Amendment C2); separate bean so {@code @Transactional} proxying can't be bypassed by self-invocation. */
@Component
public class RelayBatchClaimer {

    private final DeliveryPipelineRepositoryPort pipelinePort;
    private final SubscriptionRepositoryPort subscriptionPort;

    public RelayBatchClaimer(DeliveryPipelineRepositoryPort pipelinePort, SubscriptionRepositoryPort subscriptionPort) {
        this.pipelinePort = pipelinePort;
        this.subscriptionPort = subscriptionPort;
    }

    @Transactional
    public RelayBatchClaimResult claimAndPromote(int batchLimit, Instant asOf) {
        List<Delivery> claimed = pipelinePort.claimDue(batchLimit, asOf);

        Set<UUID> subscriptionIds = new LinkedHashSet<>();
        for (Delivery delivery : claimed) {
            subscriptionIds.add(delivery.subscriptionId());
        }
        int promotedCount = 0;
        // A zero-row promoteToHalfOpen is a normal outcome for a CLOSED/HALF_OPEN subscription, not an error.
        for (UUID subscriptionId : subscriptionIds) {
            if (subscriptionPort.promoteToHalfOpen(subscriptionId, asOf)) {
                promotedCount++;
            }
        }

        return new RelayBatchClaimResult(claimed, promotedCount);
    }
}
