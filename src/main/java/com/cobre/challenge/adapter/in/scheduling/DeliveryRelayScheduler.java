package com.cobre.challenge.adapter.in.scheduling;

import com.cobre.challenge.adapter.in.scheduling.config.RelayProperties;
import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Clock tick to relay cycle (ADR-002 §2.1); orchestration only, no business logic. */
@Component
@ConditionalOnProperty(prefix = "challenge.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
class DeliveryRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRelayScheduler.class);

    private final DispatchPendingDeliveriesUseCase dispatchPendingDeliveriesUseCase;
    private final RelayProperties relayProperties;

    DeliveryRelayScheduler(
            DispatchPendingDeliveriesUseCase dispatchPendingDeliveriesUseCase, RelayProperties relayProperties) {
        this.dispatchPendingDeliveriesUseCase = dispatchPendingDeliveriesUseCase;
        this.relayProperties = relayProperties;
    }

    /** Must never let an exception escape: that would cancel the @Scheduled task (ADR-002 §2.1, OWASP A10). */
    @Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")
    void pollOnce() {
        try {
            dispatchPendingDeliveriesUseCase.dispatch(
                    new DispatchPendingDeliveriesCommand(relayProperties.batchLimit(), Instant.now()));
        } catch (Throwable t) {
            log.warn("relay cycle failed", t);
        }
    }
}
