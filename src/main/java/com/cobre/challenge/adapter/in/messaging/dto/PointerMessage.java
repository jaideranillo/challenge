package com.cobre.challenge.adapter.in.messaging.dto;

import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses one SQS pointer message into an {@link AttemptDeliveryCommand}.
 *
 * <p>{@code delivery_id} and {@code traceparent} are read from the message attribute first, the
 * body second (ADR-004 §1); {@code subscriptionId} and {@code attemptHint} come from the body
 * only. A message that yields no {@code delivery_id} anywhere, or whose body cannot be parsed at
 * all, raises {@link UnparsablePointerMessageException} instead of returning a command.
 */
public final class PointerMessage {

    private static final String DELIVERY_ID_ATTRIBUTE = "delivery_id";
    private static final String TRACEPARENT_ATTRIBUTE = "traceparent";

    private PointerMessage() {
    }

    public static AttemptDeliveryCommand toCommand(Message message, ObjectMapper objectMapper) {
        Optional<UUID> deliveryIdAttribute =
                attributeValue(message, DELIVERY_ID_ATTRIBUTE).flatMap(PointerMessage::tryParseUuid);
        Optional<String> traceparentAttribute = attributeValue(message, TRACEPARENT_ATTRIBUTE);

        try {
            Envelope envelope = objectMapper.readValue(message.body(), Envelope.class);
            UUID deliveryId = deliveryIdAttribute.orElseGet(envelope::deliveryId);
            if (deliveryId == null) {
                throw new UnparsablePointerMessageException(Optional.empty(), null);
            }
            String traceparent = traceparentAttribute.orElse(envelope.traceparent());
            return new AttemptDeliveryCommand(
                    deliveryId,
                    Objects.requireNonNull(envelope.subscriptionId()),
                    envelope.attemptHint(),
                    Optional.ofNullable(traceparent));
        } catch (UnparsablePointerMessageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnparsablePointerMessageException(deliveryIdAttribute, e);
        }
    }

    private static Optional<String> attributeValue(Message message, String name) {
        return Optional.ofNullable(message.messageAttributes().get(name)).map(MessageAttributeValue::stringValue);
    }

    private static Optional<UUID> tryParseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private record Envelope(UUID deliveryId, UUID subscriptionId, int attemptHint, String traceparent) {
    }

    /** Raised when a pointer message cannot be turned into a command; carries the delivery_id if one was known. */
    public static final class UnparsablePointerMessageException extends RuntimeException {

        private final Optional<UUID> deliveryId;

        UnparsablePointerMessageException(Optional<UUID> deliveryId, Throwable cause) {
            super("Unparsable pointer message" + deliveryId.map(id -> " delivery_id=" + id).orElse(" (no delivery_id)"), cause);
            this.deliveryId = deliveryId;
        }

        public Optional<UUID> deliveryId() {
            return deliveryId;
        }
    }
}
