package com.cobre.challenge.adapter.in.scheduling;

import com.cobre.challenge.adapter.in.scheduling.config.RelayProperties;
import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
    private static final String SPAN_RELAY_POLL = "notification.relay.poll";

    private final DispatchPendingDeliveriesUseCase dispatchPendingDeliveriesUseCase;
    private final RelayProperties relayProperties;
    private final Tracer tracer;
    private final DispatchSpanRecorder dispatchSpanRecorder;

    DeliveryRelayScheduler(
            DispatchPendingDeliveriesUseCase dispatchPendingDeliveriesUseCase,
            RelayProperties relayProperties,
            Tracer tracer,
            DispatchSpanRecorder dispatchSpanRecorder) {
        this.dispatchPendingDeliveriesUseCase = dispatchPendingDeliveriesUseCase;
        this.relayProperties = relayProperties;
        this.tracer = tracer;
        this.dispatchSpanRecorder = dispatchSpanRecorder;
    }

    /**
     * Must never let an exception escape: that would cancel the @Scheduled task (ADR-002 §2.1,
     * OWASP A10). {@code notification.relay.poll} is its own root span (ADR-008 §2.3/§2.6),
     * parent of nothing in the business flow, so a poll that claims zero rows is still visible.
     */
    @Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")
    void pollOnce() {
        Span span = tracer.nextSpan().name(SPAN_RELAY_POLL).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            DispatchPendingDeliveriesResult result = dispatchPendingDeliveriesUseCase.dispatch(
                    new DispatchPendingDeliveriesCommand(relayProperties.batchLimit(), Instant.now()));
            dispatchSpanRecorder.record(result.entries());
        } catch (Throwable t) {
            span.error(t);
            log.warn("relay cycle failed", t);
        } finally {
            span.end();
        }
    }
}
