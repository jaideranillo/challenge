package com.cobre.challenge.adapter.in.messaging;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.adapter.out.messaging.dto.NotificationEnvelope;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-003 §1.1 / ADR-004 §1: the dedicated DLQ-consumer actor. Correlates a poison message back
 * to its {@code deliveries} row via the {@code delivery_id} message attribute (TASK-007-16),
 * falling back to the body only when the attribute is absent, marks the row {@code FAILED},
 * deletes the message, and alerts.
 *
 * <p>{@code FAILED}, never {@code DEAD}: {@code DEAD} is a business outcome reserved for a
 * webhook actually attempted (ADR-004 §1). This consumer reports its own inability to process
 * the pointer, not a delivery outcome, and {@code markFailed}'s guard
 * ({@code status IN ('QUEUED','PROCESSING')}) keeps a late DLQ message from overwriting a row
 * that already reached a terminal state.
 *
 * <p>Attribute first, body second: correlation must survive a body that fails to parse. An
 * uncorrelatable message (no attribute, no parseable body) is not a lost delivery -- it is a
 * message this system never produced -- and is logged in full as an operational/security alert
 * rather than guessed at.
 *
 * <p>No {@code synchronized} anywhere: {@link SqsClient} is the synchronous, thread-safe client
 * whose blocking I/O unmounts a virtual thread correctly on its own.
 */
@Component
public class DeliveryDlqConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDlqConsumer.class);
    private static final String DELIVERY_ID_ATTRIBUTE = "delivery_id";
    private static final String TRACEPARENT_ATTRIBUTE = "traceparent";
    private static final String DLQ_ARRIVAL_COUNTER = "notification.delivery.dlq.arrival";
    private static final String MDC_DELIVERY_ID = "delivery_id";
    private static final String FAILURE_REASON = "poison message: max receive count exceeded";
    private static final String SPAN_DLQ = "notification.dlq";
    private static final int WAIT_TIME_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final DeliveryPipelineRepositoryPort pipelinePort;
    private final Clock clock;
    private final WorkerProperties workerProperties;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter dlqArrivalCounter;
    private final String queueUrl;

    private volatile boolean running;
    private Thread loopThread;

    public DeliveryDlqConsumer(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            SqsProperties sqsProperties,
            DeliveryPipelineRepositoryPort pipelinePort,
            Clock clock,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry,
            Tracer tracer,
            Propagator propagator) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.pipelinePort = pipelinePort;
        this.clock = clock;
        this.workerProperties = workerProperties;
        this.tracer = tracer;
        this.propagator = propagator;
        // No client_id/subscription_id tag: every DLQ arrival pages on-call regardless of tenant (ADR-002 §3).
        this.dlqArrivalCounter = meterRegistry.counter(DLQ_ARRIVAL_COUNTER);
        this.queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveriesDlq())
                        .build())
                .queueUrl();
    }

    @Override
    public void start() {
        if (!workerProperties.enabled()) {
            return;
        }
        running = true;
        loopThread = Thread.ofVirtual().start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Throwable t) {
                log.error("DLQ poll cycle failed", t);
            }
        }
    }

    /** Package-private test seam: drives exactly one receive/process/delete cycle. */
    void pollOnce() {
        List<Message> messages = sqsClient
                .receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(WAIT_TIME_SECONDS)
                        .maxNumberOfMessages(MAX_MESSAGES)
                        .messageAttributeNames("All")
                        .build())
                .messages();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Message message : messages) {
                executor.submit(() -> processMessage(message));
            }
        }
    }

    private void processMessage(Message message) {
        // MDC ownership: this adapter opens the scope at the boundary and is the only thing that
        // closes it, unconditionally, in the finally below, alongside the span (ADR-008 §3.2).
        Span span = startDlqSpan(message);
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            dlqArrivalCounter.increment();
            Optional<String> deliveryId = extractDeliveryId(message);
            if (deliveryId.isPresent()) {
                MDC.put(MDC_DELIVERY_ID, deliveryId.get());
                correlate(deliveryId.get());
            } else {
                log.error("Uncorrelatable DLQ message, no delivery_id attribute or parseable body: {}", message);
            }
        } catch (Throwable t) {
            span.error(t);
            log.error("Unexpected error processing DLQ message", t);
        } finally {
            span.end();
            MDC.clear();
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    /**
     * Extracts the remote trace context (message attribute first, envelope-body second) and
     * opens {@code notification.dlq} as its child. An absent or malformed value starts a new root
     * trace rather than throwing (OWASP A10): an observability failure must never fail this
     * correlation.
     */
    private Span startDlqSpan(Message message) {
        Span.Builder builder;
        try {
            Map<String, String> carrier = new HashMap<>();
            extractTraceparent(message).ifPresent(tp -> carrier.put(TRACEPARENT_ATTRIBUTE, tp));
            builder = propagator.extract(carrier, Map::get);
        } catch (RuntimeException e) {
            log.debug("Could not extract trace context from DLQ message; starting a new root trace", e);
            builder = tracer.spanBuilder();
        }
        return builder.name(SPAN_DLQ).start();
    }

    private Optional<String> extractTraceparent(Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(TRACEPARENT_ATTRIBUTE);
        if (attribute != null && attribute.stringValue() != null) {
            return Optional.of(attribute.stringValue());
        }
        return extractTraceparentFromBody(message.body());
    }

    private Optional<String> extractTraceparentFromBody(String body) {
        try {
            NotificationEnvelope envelope = objectMapper.readValue(body, NotificationEnvelope.class);
            return Optional.ofNullable(envelope.traceparent());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void correlate(String deliveryIdValue) {
        UUID deliveryId = UUID.fromString(deliveryIdValue);
        boolean updated = pipelinePort.markFailed(deliveryId, FAILURE_REASON, Instant.now(clock));
        if (updated) {
            log.error("DLQ message correlated, marked delivery_id={} FAILED", deliveryId);
        } else {
            log.debug("DLQ message for delivery_id={} ignored: row already terminal", deliveryId);
        }
    }

    private Optional<String> extractDeliveryId(Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(DELIVERY_ID_ATTRIBUTE);
        if (attribute != null && attribute.stringValue() != null) {
            return Optional.of(attribute.stringValue());
        }
        return extractDeliveryIdFromBody(message.body());
    }

    private Optional<String> extractDeliveryIdFromBody(String body) {
        try {
            NotificationEnvelope envelope = objectMapper.readValue(body, NotificationEnvelope.class);
            return Optional.of(envelope.deliveryId().toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
