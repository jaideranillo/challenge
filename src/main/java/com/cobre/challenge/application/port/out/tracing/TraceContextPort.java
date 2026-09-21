package com.cobre.challenge.application.port.out.tracing;

import java.util.Optional;

/**
 * Framework-free access to the current W3C {@code traceparent}, for the ingest use case.
 *
 * <p>ADR-003 Amendment A3 makes {@code trace_context} a value the ingest use case writes and
 * the worker reads; ADR-002 §3.1 defines that value as the current W3C {@code traceparent}.
 * Reading it means touching Micrometer/OpenTelemetry, and a use case in this codebase carries
 * no framework type but {@code @Transactional}. ADR-002 Amendment C3 adds this port for that
 * reason.
 *
 * <p>One method only: ingest writes one value, so this is the whole surface. Not a general
 * tracing facade — no span creation, no MDC, no baggage, no {@code currentTraceId()}
 * (YAGNI, Effective Java Item 64).
 */
public interface TraceContextPort {

    /**
     * Returns the current W3C {@code traceparent}, if a span is active.
     *
     * <p>{@link Optional#empty()} when no span is active is normal, not an error.
     * {@code deliveries.trace_context} is nullable ({@code V2}), and ADR-002 §3.1's
     * consumer-side fallback already handles an absent value: the message attribute is tried
     * first.
     *
     * <p>Never throws. An observability read must not be able to fail an ingest whose data is
     * otherwise fine (OWASP A10). The adapter swallows any failure and returns
     * {@link Optional#empty()}.
     */
    Optional<String> currentTraceparent();
}
