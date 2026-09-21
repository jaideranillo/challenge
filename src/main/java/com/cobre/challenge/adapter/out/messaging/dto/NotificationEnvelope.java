package com.cobre.challenge.adapter.out.messaging.dto;

import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import java.util.UUID;

/**
 * The wire shape of ADR-004 SS1's envelope: exactly {@link DeliveryPointer}'s four scalar
 * fields, flattening its {@code Optional<String> traceparent} to a plain, possibly-null
 * {@code String} so Jackson serializes it as a present key with a null value rather than an
 * {@code Optional} wrapper object.
 */
public record NotificationEnvelope(UUID deliveryId, UUID subscriptionId, int attemptHint, String traceparent) {

	public static NotificationEnvelope from(DeliveryPointer pointer) {
		return new NotificationEnvelope(
				pointer.deliveryId(), pointer.subscriptionId(), pointer.attemptHint(), pointer.traceparent().orElse(null));
	}
}
