package com.cobre.challenge.adapter.out.tracing;

import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the active span's context and formats it as a W3C {@code traceparent}
 * ({@code 00-<trace-id>-<span-id>-<flags>}), the same shape an SQS {@code traceparent} message
 * attribute carries, so the worker's fallback to {@code deliveries.trace_context} (ADR-002
 * §3.1) parses a value in the exact format it expects.
 *
 * <p>Never throws (OWASP A10): no active span, no tracer, or a malformed context all yield
 * {@link Optional#empty()}. No {@code ThreadLocal} of its own and nothing held across a
 * blocking call — the span context is scoped by the tracer, this adapter only reads it.
 */
@Component
public class MicrometerTraceContextAdapter implements TraceContextPort {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTraceContextAdapter.class);
    private static final String VERSION = "00";
    private static final String SAMPLED_FLAGS = "01";
    private static final String UNSAMPLED_FLAGS = "00";

    private final Tracer tracer;

    public MicrometerTraceContextAdapter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Optional<String> currentTraceparent() {
        try {
            Span span = tracer.currentSpan();
            if (span == null) {
                return Optional.empty();
            }

            TraceContext context = span.context();
            String flags = Boolean.TRUE.equals(context.sampled()) ? SAMPLED_FLAGS : UNSAMPLED_FLAGS;
            return Optional.of(VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + flags);
        } catch (RuntimeException e) {
            log.debug("Could not read current trace context", e);
            return Optional.empty();
        }
    }
}
