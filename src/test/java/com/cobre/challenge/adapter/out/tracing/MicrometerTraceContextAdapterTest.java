package com.cobre.challenge.adapter.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MicrometerTraceContextAdapterTest {

    private static final Pattern W3C_TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void returnsW3cTraceparentCarryingTheActiveSpanTraceId() {
        Tracer tracer = new StubTracer(new StubSpan(TRACE_ID, SPAN_ID, true));
        MicrometerTraceContextAdapter adapter = new MicrometerTraceContextAdapter(tracer, new W3cLikePropagator());

        Optional<String> traceparent = adapter.currentTraceparent();

        assertThat(traceparent).contains("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(traceparent.get()).matches(W3C_TRACEPARENT);
    }

    @Test
    void returnsEmptyWithNoActiveSpan() {
        Tracer tracer = new StubTracer(null);
        MicrometerTraceContextAdapter adapter = new MicrometerTraceContextAdapter(tracer, new W3cLikePropagator());

        assertThat(adapter.currentTraceparent()).isEmpty();
    }

    /** Stands in for the Boot-configured W3C propagator. */
    private static final class W3cLikePropagator implements Propagator {

        @Override
        public List<String> fields() {
            return List.of("traceparent");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            setter.set(carrier, "traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-" + flags);
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            throw new UnsupportedOperationException("not used by this adapter");
        }
    }

    /**
     * Real {@link Tracer}/{@link Span}/{@link TraceContext} contracts, standing in for a live
     * span without a full tracer SDK. Only {@link #currentSpan()} and {@link Span#context()}
     * are exercised by the adapter under test; every other method is unused by it and throws.
     */
    private static final class StubTracer implements Tracer {

        private final Span currentSpan;

        StubTracer(Span currentSpan) {
            this.currentSpan = currentSpan;
        }

        @Override
        public Span currentSpan() {
            return currentSpan;
        }

        @Override
        public Span nextSpan() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span nextSpan(Span parent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tracer.SpanInScope withSpan(Span span) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.ScopedSpan startScopedSpan(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span.Builder spanBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TraceContext.Builder traceContextBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.CurrentTraceContext currentTraceContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.SpanCustomizer currentSpanCustomizer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, String> getAllBaggage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(TraceContext traceContext, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name, String value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubSpan implements Span {

        private final TraceContext context;

        StubSpan(String traceId, String spanId, boolean sampled) {
            this.context = new StubTraceContext(traceId, spanId, sampled);
        }

        @Override
        public TraceContext context() {
            return context;
        }

        @Override
        public boolean isNoop() {
            return false;
        }

        @Override
        public Span start() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span name(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span event(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span event(String value, long time, java.util.concurrent.TimeUnit timeUnit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span tag(String key, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span error(Throwable throwable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void end() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void end(long time, java.util.concurrent.TimeUnit timeUnit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abandon() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span remoteServiceName(String remoteServiceName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span remoteIpAndPort(String ip, int port) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubTraceContext(String traceId, String spanId, Boolean sampled) implements TraceContext {

        @Override
        public String parentId() {
            return null;
        }
    }
}
