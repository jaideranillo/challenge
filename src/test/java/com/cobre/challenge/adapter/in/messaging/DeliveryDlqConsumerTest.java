package com.cobre.challenge.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives {@code pollOnce()} directly, per the task's phase rule: plain JUnit, mocked
 * {@link SqsClient} and {@link DeliveryPipelineRepositoryPort}, no Spring context.
 */
class DeliveryDlqConsumerTest {

    private static final String DLQ_URL = "https://sqs.example/deliveries-dlq";
    private static final String FAILURE_REASON = "poison message: max receive count exceeded";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final DeliveryPipelineRepositoryPort pipelinePort = mock(DeliveryPipelineRepositoryPort.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WorkerProperties workerProperties = new WorkerProperties(
            true,
            Duration.ofSeconds(20),
            10,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            new WorkerProperties.Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
            new WorkerProperties.CircuitBreaker(5, Duration.ofSeconds(30), Duration.ofHours(1)),
            Duration.ofHours(1),
            1024);

    private final DeliveryDlqConsumer consumer;

    DeliveryDlqConsumerTest() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(DLQ_URL).build());
        SqsProperties sqsProperties =
                new SqsProperties(Optional.empty(), "us-east-1", null, new SqsProperties.Queues("deliveries", "deliveries-dlq"));
        this.consumer = new DeliveryDlqConsumer(
                sqsClient,
                objectMapper,
                sqsProperties,
                pipelinePort,
                clock,
                workerProperties,
                new SimpleMeterRegistry(),
                Tracer.NOOP,
                Propagator.NOOP);
    }

    private void stubReceive(Message... messages) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private static Message messageWithAttribute(UUID deliveryId, String receiptHandle) {
        return Message.builder()
                .body("not used")
                .receiptHandle(receiptHandle)
                .messageAttributes(Map.of(
                        "delivery_id",
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(deliveryId.toString())
                                .build()))
                .build();
    }

    @Test
    void attributeCarryingMessageCallsMarkFailedExactlyOnceThenDeletes() {
        UUID deliveryId = UUID.randomUUID();
        Message message = messageWithAttribute(deliveryId, "receipt-1");
        stubReceive(message);
        when(pipelinePort.markFailed(deliveryId, FAILURE_REASON, NOW)).thenReturn(true);

        consumer.pollOnce();

        verify(pipelinePort, times(1)).markFailed(deliveryId, FAILURE_REASON, NOW);
        ArgumentCaptor<DeleteMessageRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient, times(1)).deleteMessage(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().receiptHandle()).isEqualTo("receipt-1");
        assertThat(deleteCaptor.getValue().queueUrl()).isEqualTo(DLQ_URL);
    }

    @Test
    void noAttributeButParseableBodyFallsBackToBodyDeliveryId() {
        UUID deliveryId = UUID.randomUUID();
        String body = "{\"deliveryId\":\"" + deliveryId + "\",\"subscriptionId\":\""
                + UUID.randomUUID() + "\",\"attemptHint\":1,\"traceparent\":null}";
        Message message = Message.builder().body(body).receiptHandle("receipt-2").build();
        stubReceive(message);
        when(pipelinePort.markFailed(deliveryId, FAILURE_REASON, NOW)).thenReturn(true);

        consumer.pollOnce();

        verify(pipelinePort, times(1)).markFailed(deliveryId, FAILURE_REASON, NOW);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void undeserializableBodyStillCorrelatesUsingOnlyTheDeliveryIdAttribute() {
        UUID deliveryId = UUID.randomUUID();
        Message message = Message.builder()
                .body("{not-json-at-all-and-truncated")
                .receiptHandle("receipt-3")
                .messageAttributes(Map.of(
                        "delivery_id",
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(deliveryId.toString())
                                .build()))
                .build();
        stubReceive(message);
        when(pipelinePort.markFailed(deliveryId, FAILURE_REASON, NOW)).thenReturn(true);

        consumer.pollOnce();

        // Body is never successfully parsed on this path: the attribute alone drives correlation.
        verify(pipelinePort, times(1)).markFailed(deliveryId, FAILURE_REASON, NOW);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void noAttributeAndNoParseableBodyYieldsNoMarkFailedCallAndStillDeletes() {
        Message message =
                Message.builder().body("{not-json-at-all").receiptHandle("receipt-4").build();
        stubReceive(message);

        consumer.pollOnce();

        verify(pipelinePort, never()).markFailed(any(), any(), any());
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void markFailedReturningFalseDoesNotThrowAndMessageIsStillDeleted() {
        UUID deliveryId = UUID.randomUUID();
        Message message = messageWithAttribute(deliveryId, "receipt-5");
        stubReceive(message);
        when(pipelinePort.markFailed(deliveryId, FAILURE_REASON, NOW)).thenReturn(false);

        assertThatCode(consumer::pollOnce).doesNotThrowAnyException();

        verify(pipelinePort, times(1)).markFailed(deliveryId, FAILURE_REASON, NOW);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void throwingMarkFailedDoesNotEscapePollOnce() {
        UUID deliveryId = UUID.randomUUID();
        Message message = messageWithAttribute(deliveryId, "receipt-6");
        stubReceive(message);
        doThrow(new RuntimeException("boom")).when(pipelinePort).markFailed(eq(deliveryId), any(), any());

        assertThatCode(consumer::pollOnce).doesNotThrowAnyException();

        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void receiveRequestTargetsTheDlqUrlWithLongPollAndBatchSize() {
        stubReceive();

        consumer.pollOnce();

        ArgumentCaptor<ReceiveMessageRequest> requestCaptor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqsClient, times(1)).receiveMessage(requestCaptor.capture());
        ReceiveMessageRequest request = requestCaptor.getValue();
        assertThat(request.queueUrl()).isEqualTo(DLQ_URL);
        assertThat(request.waitTimeSeconds()).isEqualTo(20);
        assertThat(request.maxNumberOfMessages()).isEqualTo(10);
    }
}
