package com.cobre.challenge.adapter.in.scheduling;

import com.cobre.challenge.application.port.in.pipeline.dto.DispatchedDeliveryEntry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates {@code notification.dispatch} (ADR-008 §2.3/§2.6): one span per claimed delivery, each
 * a child of that delivery's own extracted trace context, opened in the relay adapter rather than
 * the use case (ADR-002 Amendment C3).
 */
@Component
class DispatchSpanRecorder {

    private static final Logger log = LoggerFactory.getLogger(DispatchSpanRecorder.class);
    private static final String SPAN_NAME = "notification.dispatch";
    private static final String TRACEPARENT_KEY = "traceparent";
    private static final String ATTR_DELIVERY_ID = "delivery_id";
    private static final String ATTR_PUBLISHED = "published";

    private final Tracer tracer;
    private final Propagator propagator;

    DispatchSpanRecorder(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Never throws (OWASP A10): a span-recording failure must not fail, delay or skip the relay cycle. */
    void record(List<DispatchedDeliveryEntry> entries) {
        for (DispatchedDeliveryEntry entry : entries) {
            recordOne(entry);
        }
    }

    private void recordOne(DispatchedDeliveryEntry entry) {
        try {
            Span span = openSpan(entry);
            span.end();
        } catch (RuntimeException e) {
            log.debug("Could not record notification.dispatch span for delivery_id={}", entry.deliveryId(), e);
        }
    }

    private Span openSpan(DispatchedDeliveryEntry entry) {
        Span.Builder builder;
        try {
            Map<String, String> carrier = new HashMap<>();
            entry.traceparent().ifPresent(tp -> carrier.put(TRACEPARENT_KEY, tp));
            builder = propagator.extract(carrier, Map::get);
        } catch (RuntimeException e) {
            log.debug("Could not extract trace context for delivery_id={}; starting a new root span", entry.deliveryId(), e);
            builder = tracer.spanBuilder();
        }
        return builder.name(SPAN_NAME)
                .tag(ATTR_DELIVERY_ID, entry.deliveryId().toString())
                .tag(ATTR_PUBLISHED, String.valueOf(entry.published()))
                .start();
    }
}
