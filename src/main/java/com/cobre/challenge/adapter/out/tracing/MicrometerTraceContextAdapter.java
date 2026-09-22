package com.cobre.challenge.adapter.out.tracing;

import com.cobre.challenge.application.port.out.tracing.TraceContextPort;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the active span's context and formats it as a W3C {@code traceparent} by injecting it,
 * with the configured {@link Propagator} (ADR-008 §2.1), into a single-entry carrier. The
 * propagator is the only thing that knows the wire format; this adapter never concatenates one.
 *
 * <p>Never throws (OWASP A10): no active span, no tracer, or a malformed context all yield
 * {@link Optional#empty()}. No {@code ThreadLocal} of its own and nothing held across a
 * blocking call — the span context is scoped by the tracer, this adapter only reads it.
 */
@Component
public class MicrometerTraceContextAdapter implements TraceContextPort {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTraceContextAdapter.class);
    private static final String TRACEPARENT_KEY = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContextAdapter(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Optional<String> currentTraceparent() {
        try {
            Span span = tracer.currentSpan();
            if (span == null) {
                return Optional.empty();
            }

            Map<String, String> carrier = new HashMap<>();
            propagator.inject(span.context(), carrier, Map::put);
            return Optional.ofNullable(carrier.get(TRACEPARENT_KEY));
        } catch (RuntimeException e) {
            log.debug("Could not read current trace context", e);
            return Optional.empty();
        }
    }
}
