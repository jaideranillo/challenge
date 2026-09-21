package com.cobre.challenge.adapter.out.messaging;

import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.adapter.out.messaging.dto.NotificationEnvelope;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The ADR-004 SS1 publisher: turns a {@link DeliveryPointer} into a {@code SendMessage} on the
 * {@code deliveries} queue. First implementation of {@link NotificationQueuePort}.
 *
 * <p>The queue URL is resolved once here, at construction, via {@code GetQueueUrl} — not per
 * publish. This path exists to shave latency off ingest; a second round trip per message would
 * defeat that.
 *
 * <p>No {@code synchronized} anywhere in this class: {@link SqsClient} is the synchronous,
 * thread-safe client, and its blocking HTTP I/O unmounts a virtual thread correctly on its own
 * (spring.threads.virtual.enabled=true). Guarding it with {@code synchronized} would instead
 * pin the calling virtual thread's carrier for a full network round trip.
 *
 * <p>No exception handling here: SDK exceptions propagate. The caller's catch-all around a
 * best-effort publish (ADR-002 SS1.1 step 5) is a separate concern.
 */
@Component
public class SqsNotificationQueueAdapter implements NotificationQueuePort {

	private static final String TRACEPARENT_ATTRIBUTE = "traceparent";
	private static final int BATCH_CHUNK_SIZE = 10;

	private final SqsClient sqsClient;
	private final ObjectMapper objectMapper;
	private final String queueUrl;

	public SqsNotificationQueueAdapter(SqsClient sqsClient, ObjectMapper objectMapper, SqsProperties properties) {
		this.sqsClient = sqsClient;
		this.objectMapper = objectMapper;
		this.queueUrl = sqsClient
				.getQueueUrl(GetQueueUrlRequest.builder()
						.queueName(properties.queues().deliveries())
						.build())
				.queueUrl();
	}

	@Override
	public void publish(DeliveryPointer pointer) {
		sqsClient.sendMessage(SendMessageRequest.builder()
				.queueUrl(queueUrl)
				.messageBody(toJson(pointer))
				.messageAttributes(traceparentAttribute(pointer))
				.build());
	}

	@Override
	public void publishBatch(List<DeliveryPointer> pointers) {
		for (int start = 0; start < pointers.size(); start += BATCH_CHUNK_SIZE) {
			List<DeliveryPointer> chunk = pointers.subList(start, Math.min(start + BATCH_CHUNK_SIZE, pointers.size()));
			sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
					.queueUrl(queueUrl)
					.entries(toBatchEntries(chunk))
					.build());
		}
	}

	private List<SendMessageBatchRequestEntry> toBatchEntries(List<DeliveryPointer> chunk) {
		List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
		for (int i = 0; i < chunk.size(); i++) {
			DeliveryPointer pointer = chunk.get(i);
			entries.add(SendMessageBatchRequestEntry.builder()
					.id(Integer.toString(i))
					.messageBody(toJson(pointer))
					.messageAttributes(traceparentAttribute(pointer))
					.build());
		}
		return entries;
	}

	private Map<String, MessageAttributeValue> traceparentAttribute(DeliveryPointer pointer) {
		return pointer.traceparent()
				.map(traceparent -> Map.of(
						TRACEPARENT_ATTRIBUTE,
						MessageAttributeValue.builder()
								.dataType("String")
								.stringValue(traceparent)
								.build()))
				.orElseGet(Map::of);
	}

	private String toJson(DeliveryPointer pointer) {
		return objectMapper.writeValueAsString(NotificationEnvelope.from(pointer));
	}
}
