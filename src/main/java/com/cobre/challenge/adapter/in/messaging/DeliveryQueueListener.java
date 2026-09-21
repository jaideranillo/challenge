package com.cobre.challenge.adapter.in.messaging;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.in.messaging.dto.PointerMessage;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.application.port.in.pipeline.AttemptDeliveryUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final SqsClient sqsClient;
    private final AttemptDeliveryUseCase attemptDeliveryUseCase;
    private final WorkerProperties workerProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService fanOutExecutor;
    private final String queueUrl;

    private volatile boolean running;

    DeliveryQueueListener(
            SqsClient sqsClient,
            AttemptDeliveryUseCase attemptDeliveryUseCase,
            WorkerProperties workerProperties,
            SqsProperties sqsProperties,
            ObjectMapper objectMapper) {
        this(
                sqsClient,
                attemptDeliveryUseCase,
                workerProperties,
                objectMapper,
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
            ExecutorService fanOutExecutor,
            String queueUrl) {
        this.sqsClient = sqsClient;
        this.attemptDeliveryUseCase = attemptDeliveryUseCase;
        this.workerProperties = workerProperties;
        this.objectMapper = objectMapper;
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
            log.error(
                    "Undeliverable pointer message; deleting without an attempt. delivery_id={}",
                    e.deliveryId().map(Object::toString).orElse("unknown"),
                    e);
            deleteMessage(message);
            return;
        }

        try {
            AttemptDeliveryResult result = attemptDeliveryUseCase.attempt(command);
            deleteMessage(message);
            log.info(
                    "Delivery attempt returned; deleting message. delivery_id={} subscription_id={} attempt_hint={} outcome_present={}",
                    command.deliveryId(),
                    command.subscriptionId(),
                    command.attemptHint(),
                    result.outcome().isPresent());
        } catch (Throwable t) {
            log.error(
                    "Delivery attempt threw; leaving message for redelivery. delivery_id={} subscription_id={}",
                    command.deliveryId(),
                    command.subscriptionId(),
                    t);
        }
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
