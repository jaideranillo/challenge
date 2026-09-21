package com.cobre.challenge.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.in.pipeline.AttemptDeliveryUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives {@code pollOnce()} directly per the task's phase rule: plain JUnit, mocked
 * {@link SqsClient} and {@link AttemptDeliveryUseCase}, no Spring context.
 */
class DeliveryQueueListenerTest {

    private static final String QUEUE_URL = "https://sqs.example/deliveries";

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final AttemptDeliveryUseCase useCase = mock(AttemptDeliveryUseCase.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService fanOutExecutor = Executors.newVirtualThreadPerTaskExecutor();
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

    private final DeliveryQueueListener listener =
            new DeliveryQueueListener(sqsClient, useCase, workerProperties, objectMapper, fanOutExecutor, QUEUE_URL);

    private void stubReceive(Message... messages) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private static Message pointerMessage(UUID deliveryId, UUID subscriptionId, String receiptHandle) {
        String body = "{\"deliveryId\":\"" + deliveryId + "\",\"subscriptionId\":\"" + subscriptionId
                + "\",\"attemptHint\":1,\"traceparent\":null}";
        return Message.builder()
                .body(body)
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
    void normalOutcomeResultsInExactlyOneDeleteForThatReceiptHandle() {
        UUID deliveryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Message message = pointerMessage(deliveryId, subscriptionId, "receipt-1");
        stubReceive(message);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.of(DeliveryStatus.DELIVERED, AttemptOutcome.SUCCESS));

        listener.pollOnce();

        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient, times(1)).deleteMessage(captor.capture());
        assertThat(captor.getValue().receiptHandle()).isEqualTo("receipt-1");
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
    }

    @Test
    void lostClaimResultAlsoDeletes() {
        Message message = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-2");
        stubReceive(message);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.noAttempt(DeliveryStatus.QUEUED));

        listener.pollOnce();

        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deferredResultAlsoDeletes() {
        Message message = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-3");
        stubReceive(message);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.noAttempt(DeliveryStatus.QUEUED));

        listener.pollOnce();

        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deleteDecisionIgnoresWhetherOutcomeIsEmptyOrPresent() {
        Message emptyOutcomeMessage = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-empty");
        Message presentOutcomeMessage = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-present");

        stubReceive(emptyOutcomeMessage);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.noAttempt(DeliveryStatus.QUEUED));
        listener.pollOnce();
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));

        Mockito.clearInvocations(sqsClient);
        stubReceive(presentOutcomeMessage);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.of(DeliveryStatus.RETRYING, AttemptOutcome.RETRYABLE));
        listener.pollOnce();
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void throwingUseCaseDoesNotDeleteAndDoesNotEscapePollOnce() {
        Message message = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-4");
        stubReceive(message);
        when(useCase.attempt(any(AttemptDeliveryCommand.class))).thenThrow(new RuntimeException("boom"));

        assertThatCode(listener::pollOnce).doesNotThrowAnyException();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deleteMessageIsNeverCalledBeforeAttemptReturns() {
        Message message = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-5");
        stubReceive(message);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.of(DeliveryStatus.DELIVERED, AttemptOutcome.SUCCESS));

        listener.pollOnce();

        InOrder order = Mockito.inOrder(useCase, sqsClient);
        order.verify(useCase).attempt(any(AttemptDeliveryCommand.class));
        order.verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void changeMessageVisibilityIsNeverCalledOnAnyPath() {
        Message success = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-6");
        Message failure = pointerMessage(UUID.randomUUID(), UUID.randomUUID(), "receipt-7");
        stubReceive(success, failure);
        when(useCase.attempt(any(AttemptDeliveryCommand.class)))
                .thenReturn(AttemptDeliveryResult.of(DeliveryStatus.DELIVERED, AttemptOutcome.SUCCESS))
                .thenThrow(new RuntimeException("boom"));

        listener.pollOnce();

        verify(sqsClient, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void messageWithNoDeliveryIdIsDeletedNotPassedToUseCaseAndNotAnAttemptCall() {
        Message message = Message.builder()
                .body("{not-json-at-all")
                .receiptHandle("receipt-8")
                .build();
        stubReceive(message);

        listener.pollOnce();

        verifyNoInteractions(useCase);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void receiveRequestCarriesConfiguredWaitTimeAndBatchSize() {
        stubReceive();

        listener.pollOnce();

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqsClient, times(1)).receiveMessage(captor.capture());
        ReceiveMessageRequest request = captor.getValue();
        assertThat(request.waitTimeSeconds()).isEqualTo(20);
        assertThat(request.maxNumberOfMessages()).isEqualTo(10);
    }

    @Test
    void throwingReceiveMessageDoesNotPropagateOutOfPollOnce() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new RuntimeException("SQS down"));

        assertThatCode(listener::pollOnce).doesNotThrowAnyException();

        verifyNoInteractions(useCase);
    }
}
