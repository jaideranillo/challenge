package com.cobre.challenge.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.out.webhook.dto.WebhookEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JacksonWebhookEnvelopeSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonWebhookEnvelopeSerializer serializer = new JacksonWebhookEnvelopeSerializer(objectMapper);

    private WebhookEnvelope envelope(String content) {
        return new WebhookEnvelope(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "EVT001",
                "credit_card_payment",
                "CLIENT001",
                Instant.parse("2024-03-15T09:30:22.145231Z"),
                2,
                content);
    }

    @Test
    void serializesExactlyTheSevenSnakeCaseKeys() {
        String json = serializer.serialize(envelope("plain body"));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.propertyNames()).containsExactlyInAnyOrder(
                "notification_event_id", "event_id", "event_type", "client_id", "created_at", "attempt", "content");
        assertThat(node.get("notification_event_id").asString())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void createdAtSerializesAsIsoInstantNotEpoch() {
        String json = serializer.serialize(envelope("plain body"));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("created_at").isNumber()).isFalse();
        assertThat(node.get("created_at").asString()).isEqualTo("2024-03-15T09:30:22.145231Z");
    }

    @Test
    void contentIsEmittedVerbatimEvenWhenJsonLooking() {
        String jsonLookingContent = "{\"nested\":\"value\"}";
        String json = serializer.serialize(envelope(jsonLookingContent));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").isString()).isTrue();
        assertThat(node.get("content").asString()).isEqualTo(jsonLookingContent);
    }

    @Test
    void serializingSameEnvelopeTwiceIsByteIdentical() {
        WebhookEnvelope envelope = envelope("plain body");
        assertThat(serializer.serialize(envelope)).isEqualTo(serializer.serialize(envelope));
    }

    @Test
    void compactConstructorRejectsNullField() {
        assertThatThrownBy(() -> new WebhookEnvelope(
                        null, "EVT001", "credit_card_payment", "CLIENT001", Instant.now(), 1, "content"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void compactConstructorRejectsAttemptZero() {
        assertThatThrownBy(() -> new WebhookEnvelope(
                        UUID.randomUUID(), "EVT001", "credit_card_payment", "CLIENT001", Instant.now(), 0, "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
