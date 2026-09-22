package com.cobre.challenge.adapter.in.messaging;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.in.messaging.dto.PointerMessage;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.application.port.in.pipeline.AttemptDeliveryUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * The ADR-002 §2.2 / ADR-006 §1.1 consumer loop: long-poll, fan out one virtual thread per
 * message, delete after {@link AttemptDeliveryUseCase#attempt} returns. Orchestration only.
 *
 * <p>{@code DeleteMessage} happens on every path where {@code attempt(...)} returns normally —
 * success, retry, dead, throttled, or no attempt at all (a lost claim or a bulkhead/circuit
 * deferral). The use case commits its own state before returning, so "returned normally" is the
 * whole condition; this class never reads {@link AttemptDeliveryResult#status()} or
 * {@link AttemptDeliveryResult#outcome()} to decide anything. Only a thrown {@link Throwable}
 * skips the delete, which is the crash-loop signal {@code maxReceiveCount = 3} measures.
 *
 * <p>{@code ChangeMessageVisibility} is never used: ADR-006 §1.1 removed it from the design.
 */
@Component
@ConditionalOnProperty(prefix = "challenge.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
class DeliveryQueueListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueueListener.class);
    private static final String ATTRIBUTE_ALL = "All";
    private static final Duration RECEIVE_FAILURE_BACKOFF = Duration.ofSeconds(1);
    private static final String SPAN_ATTEMPT = "notification.attempt";
    private static final String TRACEPARENT_KEY = "traceparent";
    private static final String ATTR_DELIVERY_ID = "delivery_id";
    private static final String ATTR_SUBSCRIPTION_ID = "subscription_id";
    private static final String ATTR_ATTEMPT_NUMBER = "attempt_number";
    private static final String ATTR_OUTCOME = "outcome";
    private static final String ATTR_EVENT_ID = "event_id";
    private static final String ATTR_CLIENT_ID = "client_id";
    private static final String ATTR_HTTP_STATUS_CODE = "http.response.status_code";
    private static final String MDC_DELIVERY_ID = "delivery_id";
    private static final String MDC_SUBSCRIPTION_ID = "subscription_id";

    private final SqsClient sqsClient;
    private final AttemptDeliveryUseCase attemptDeliveryUseCase;
    private final WorkerProperties workerProperties;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ExecutorService fanOutExecutor;
    private final String queueUrl;

    private volatile boolean running;

    @Autowired
    DeliveryQueueListener(
            SqsClient sqsClient,
            AttemptDeliveryUseCase attemptDeliveryUseCase,
            WorkerProperties workerProperties,
            SqsProperties sqsProperties,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator) {
        this(
                sqsClient,
                attemptDeliveryUseCase,
                workerProperties,
                objectMapper,
                tracer,
                propagator,
                Executors.newVirtualThreadPerTaskExecutor(),
                sqsClient
                        .getQueueUrl(GetQueueUrlRequest.builder()
                                .queueName(sqsProperties.queues().deliveries())
                                .build())
                        .queueUrl());
    }

    /** Test seam: an injected executor and a fixed queue URL, no real {@code GetQueueUrl} call. */
    DeliveryQueueListener(
            SqsClient sqsClient,
            AttemptDeliveryUseCase attemptDeliveryUseCase,
            WorkerProperties workerProperties,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator,
            ExecutorService fanOutExecutor,
            String queueUrl) {
        this.sqsClient = sqsClient;
        this.attemptDeliveryUseCase = attemptDeliveryUseCase;
        this.workerProperties = workerProperties;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.fanOutExecutor = fanOutExecutor;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        running = true;
        Thread.ofVirtual().name("delivery-queue-listener").start(this::runLoop);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            pollOnce();
        }
    }

    /** One receive/fan-out/await cycle; a test drives this directly instead of the real loop. */
    void pollOnce() {
        List<Message> messages;
        try {
            messages = receive();
        } catch (Throwable t) {
            log.error("receiveMessage failed; backing off before the next poll", t);
            sleepBriefly();
            return;
        }

        List<Future<?>> tasks = new ArrayList<>(messages.size());
        for (Message message : messages) {
            tasks.add(fanOutExecutor.submit(() -> handle(message)));
        }
        awaitAll(tasks);
    }

    private List<Message> receive() {
        return sqsClient
                .receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .waitTimeSeconds((int) workerProperties.waitTime().toSeconds())
                        .maxNumberOfMessages(workerProperties.batchSize())
                        .messageAttributeNames(ATTRIBUTE_ALL)
                        .build())
                .messages();
    }

    private void handle(Message message) {
        AttemptDeliveryCommand command;
        try {
            command = PointerMessage.toCommand(message, objectMapper);
        } catch (PointerMessage.UnparsablePointerMessageException e) {
            logUnparsablePointer(e);
            deleteMessage(message);
            return;
        }

        // MDC ownership: this adapter opens the scope (delivery_id, subscription_id) at the
        // boundary and is the only thing that closes it, unconditionally, in the finally below —
        // the use case may add keys inside this scope but never clears it (ADR-008 §3.2).
        MDC.put(MDC_DELIVERY_ID, command.deliveryId().toString());
        MDC.put(MDC_SUBSCRIPTION_ID, command.subscriptionId().toString());
        Span span = startAttemptSpan(command);
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            AttemptDeliveryResult result = attemptDeliveryUseCase.attempt(command);
            tagSpanFromResult(span, command, result);
            deleteMessage(message);
            log.info(
                    "Delivery attempt returned; deleting message. delivery_id={} subscription_id={} attempt_hint={} outcome_present={}",
                    command.deliveryId(),
                    command.subscriptionId(),
                    command.attemptHint(),
                    result.outcome().isPresent());
        } catch (Throwable t) {
            span.error(t);
            log.error(
                    "Delivery attempt threw; leaving message for redelivery. delivery_id={} subscription_id={}",
                    command.deliveryId(),
                    command.subscriptionId(),
                    t);
        } finally {
            span.end();
            MDC.clear();
        }
    }

    /**
     * Tags {@code notification.attempt} with the three attributes only the use case's load can
     * know (ADR-008 §5) and corrects {@code attempt_number} to the number actually attempted,
     * falling back to the pointer's hint when no attempt was made. Called before the {@code
     * span.end()} already in {@link #handle}'s {@code finally}.
     */
    private void tagSpanFromResult(Span span, AttemptDeliveryCommand command, AttemptDeliveryResult result) {
        result.outcome().ifPresent(outcome -> span.tag(ATTR_OUTCOME, outcome.name()));
        result.eventId().ifPresent(eventId -> span.tag(ATTR_EVENT_ID, eventId));
        result.clientId().ifPresent(clientId -> span.tag(ATTR_CLIENT_ID, clientId));
        result.httpResponseStatusCode().ifPresent(status -> span.tag(ATTR_HTTP_STATUS_CODE, String.valueOf(status)));
        int attemptNumber = result.attemptNumber().orElse(command.attemptHint());
        span.tag(ATTR_ATTEMPT_NUMBER, String.valueOf(attemptNumber));
    }

    /** ADR-008 §3.4: {@code UnparsablePointerMessageException} is sanitized at construction, so it is safe to log whole. */
    private void logUnparsablePointer(PointerMessage.UnparsablePointerMessageException e) {
        log.error(
                "Undeliverable pointer message; deleting without an attempt. delivery_id={}",
                e.deliveryId().map(Object::toString).orElse("unknown"),
                e);
    }

    /**
     * Extracts the remote trace context (message attribute first, envelope-body second - both
     * already folded into {@link AttemptDeliveryCommand#traceparent()} by {@link PointerMessage})
     * and opens {@code notification.attempt} as its child. An absent or malformed value starts a
     * new root trace rather than throwing (OWASP A10): an observability failure must never fail a
     * delivery.
     */
    private Span startAttemptSpan(AttemptDeliveryCommand command) {
        Span.Builder builder;
        try {
            Map<String, String> carrier = new HashMap<>();
            command.traceparent().ifPresent(tp -> carrier.put(TRACEPARENT_KEY, tp));
            builder = propagator.extract(carrier, Map::get);
        } catch (RuntimeException e) {
            log.debug("Could not extract trace context from pointer message; starting a new root trace", e);
            builder = tracer.spanBuilder();
        }
        return builder.name(SPAN_ATTEMPT)
                .tag(ATTR_DELIVERY_ID, command.deliveryId().toString())
                .tag(ATTR_SUBSCRIPTION_ID, command.subscriptionId().toString())
                .tag(ATTR_ATTEMPT_NUMBER, String.valueOf(command.attemptHint()))
                .start();
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private void awaitAll(List<Future<?>> tasks) {
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (Exception e) {
                log.error("Fan-out task failed unexpectedly outside its own handling", e);
            }
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(RECEIVE_FAILURE_BACKOFF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
