package com.cobre.challenge.adapter.in.web.ingest;

import com.cobre.challenge.adapter.in.web.ingest.dto.IngestEventRequest;
import com.cobre.challenge.adapter.in.web.ingest.dto.IngestEventResponse;
import com.cobre.challenge.application.port.in.pipeline.RegisterNotificationEventUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway ingest endpoint (ADR-002 §1.1). Maps HTTP in, delegates to the use case, maps the
 * result to HTTP out. No business logic, no repository or queue reference, no {@code clientId}
 * inspection.
 *
 * <p><strong>Unauthenticated by explicit scope cut</strong> (Tech Lead directive, 2026-09-20;
 * see the feature's Security Impact table and {@code docs/concerns.md}). This class adds no
 * {@code SecurityFilterChain}, no {@code @PreAuthorize}, and no permit-all matcher — that
 * decision is not this task's to make or work around.
 *
 * <p>{@code 202} on every accepted outcome, including a replay that created nothing and a client
 * with no matching subscription (ADR-002 §1.1 step 4, ADR-003 §2): a {@code 409} here would break
 * the producer's at-least-once retry.
 */
@RestController
@RequestMapping("/internal/events")
public class EventIngestController {

    private static final String SPAN_INGEST = "notification.ingest";

    private final RegisterNotificationEventUseCase useCase;
    private final Tracer tracer;

    public EventIngestController(RegisterNotificationEventUseCase useCase, Tracer tracer) {
        this.useCase = useCase;
        this.tracer = tracer;
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(@Valid @RequestBody IngestEventRequest request) {
        Span span = tracer.nextSpan().name(SPAN_INGEST).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            RegisterNotificationEventResult result = useCase.register(request.toCommand());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(IngestEventResponse.from(result));
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
