package com.cobre.challenge.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves the envelope against real LocalStack SQS (CLAUDE.md's testing policy names mocked
 * SQS specifically) — messages are received back through a real {@link SqsClient} and asserted
 * on, not the SDK request object the adapter built.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@SpringBootTest
class SqsNotificationQueueAdapterTest {

	@Autowired
	private NotificationQueuePort queuePort;

	@Autowired
	private SqsClient sqsClient;

	@Autowired
	private ObjectMapper objectMapper;

	private String queueUrl;

	@BeforeEach
	void resolveQueueUrl() {
		queueUrl = sqsClient
				.getQueueUrl(GetQueueUrlRequest.builder().queueName("deliveries").build())
				.queueUrl();
		drainQueue();
	}

	private void drainQueue() {
		List<Message> messages;
		do {
			messages = receiveBatch();
			for (Message message : messages) {
				sqsClient.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
			}
		} while (!messages.isEmpty());
	}

	@Test
	void publishPutsAReceivableMessageWithTheFourFields() {
		DeliveryPointer pointer = new DeliveryPointer(
				UUID.randomUUID(), UUID.randomUUID(), 2, Optional.of("00-trace-01-span-01"));

		queuePort.publish(pointer);

		Message received = awaitOneMessage();
		ObjectNode body = (ObjectNode) objectMapper.readTree(received.body());

		assertThat(body.get("deliveryId").asString()).isEqualTo(pointer.deliveryId().toString());
		assertThat(body.get("subscriptionId").asString()).isEqualTo(pointer.subscriptionId().toString());
		assertThat(body.get("attemptHint").asInt()).isEqualTo(pointer.attemptHint());
		assertThat(body.get("traceparent").asString()).isEqualTo(pointer.traceparent().get());
	}

	@Test
	void bodyHasNoFifthField() {
		DeliveryPointer pointer =
				new DeliveryPointer(UUID.randomUUID(), UUID.randomUUID(), 1, Optional.of("00-trace-02-span-02"));

		queuePort.publish(pointer);

		Message received = awaitOneMessage();
		ObjectNode body = (ObjectNode) objectMapper.readTree(received.body());

		assertThat(body.propertyNames())
				.containsExactlyInAnyOrder("deliveryId", "subscriptionId", "attemptHint", "traceparent");
	}

	@Test
	void traceparentPresentYieldsMessageAttributeAndBodyValue() {
		String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
		DeliveryPointer pointer =
				new DeliveryPointer(UUID.randomUUID(), UUID.randomUUID(), 3, Optional.of(traceparent));

		queuePort.publish(pointer);

		Message received = awaitOneMessage();
		ObjectNode body = (ObjectNode) objectMapper.readTree(received.body());

		assertThat(received.messageAttributes()).containsKey("traceparent");
		assertThat(received.messageAttributes().get("traceparent").stringValue()).isEqualTo(traceparent);
		assertThat(body.get("traceparent").asString()).isEqualTo(traceparent);
	}

	@Test
	void traceparentAbsentYieldsNoMessageAttribute() {
		DeliveryPointer pointer = new DeliveryPointer(UUID.randomUUID(), UUID.randomUUID(), 1, Optional.empty());

		queuePort.publish(pointer);

		Message received = awaitOneMessage();

		assertThat(received.messageAttributes()).doesNotContainKey("traceparent");
	}

	@Test
	void publishBatchWithMoreThanTenPointersPutsEveryMessageOnTheQueue() {
		List<DeliveryPointer> pointers = new ArrayList<>();
		for (int i = 0; i < 13; i++) {
			pointers.add(new DeliveryPointer(UUID.randomUUID(), UUID.randomUUID(), i, Optional.empty()));
		}
		Set<String> expectedDeliveryIds = pointers.stream()
				.map(p -> p.deliveryId().toString())
				.collect(java.util.stream.Collectors.toSet());

		queuePort.publishBatch(pointers);

		Set<String> receivedDeliveryIds = new java.util.HashSet<>();
		await().atMost(Duration.ofSeconds(20)).until(() -> {
			for (Message message : receiveBatch()) {
				ObjectNode body = (ObjectNode) objectMapper.readTree(message.body());
				receivedDeliveryIds.add(body.get("deliveryId").asString());
				sqsClient.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
			}
			return receivedDeliveryIds.size() == pointers.size();
		});

		assertThat(receivedDeliveryIds).isEqualTo(expectedDeliveryIds);
	}

	private Message awaitOneMessage() {
		List<Message> received = new ArrayList<>();
		await().atMost(Duration.ofSeconds(10)).until(() -> {
			received.addAll(receiveBatch());
			return !received.isEmpty();
		});
		return received.get(0);
	}

	private List<Message> receiveBatch() {
		return sqsClient
				.receiveMessage(ReceiveMessageRequest.builder()
						.queueUrl(queueUrl)
						.maxNumberOfMessages(10)
						.messageAttributeNames("All")
						.waitTimeSeconds(1)
						.build())
				.messages();
	}
}
