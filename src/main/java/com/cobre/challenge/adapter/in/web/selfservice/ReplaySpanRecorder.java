package com.cobre.challenge.adapter.in.web.selfservice;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code notification.replay} span (ADR-008 §2.5/§2.6): a child of the current
 * (HTTP server) span, tagged with the replayed delivery's id and, best-effort, linked to the
 * original delivery's trace reconstructed from its persisted {@code trace_context}.
 *
 * <p>The link is added at span-build time, before {@code start()} - the only point the
 * {@link Span.Builder} API allows one - so this runs after the use case already knows the
 * original row's trace context, rather than wrapping the use-case call itself.
 */
@Component
class ReplaySpanRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReplaySpanRecorder.class);
    private static final String SPAN_NAME = "notification.replay";
    private static final String ATTRIBUTE_REPLAY_OF_DELIVERY_ID = "notification.replay_of_delivery_id";
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    private final Tracer tracer;

    ReplaySpanRecorder(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Never throws (OWASP A10): recording this span must never fail the replay it describes.
     * Any failure, including an unparseable {@code originalTraceContext}, is swallowed and
     * logged at {@code DEBUG}, producing a span with the attribute but no link.
     */
    void record(UUID replayedDeliveryId, Optional<String> originalTraceContext) {
        try {
            Span.Builder builder = tracer.spanBuilder()
                    .name(SPAN_NAME)
                    .tag(ATTRIBUTE_REPLAY_OF_DELIVERY_ID, replayedDeliveryId.toString());
            originalTraceContext.flatMap(this::linkTo).ifPresent(builder::addLink);
            Span span = builder.start();
            span.end();
        } catch (RuntimeException e) {
            log.debug("Could not record notification.replay span for delivery_id={}", replayedDeliveryId, e);
        }
    }

    private Optional<Link> linkTo(String traceparent) {
        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        TraceContext context = tracer.traceContextBuilder()
                .traceId(matcher.group(1))
                .spanId(matcher.group(2))
                .sampled(true)
                .build();
        return Optional.of(new Link(context));
    }
}
