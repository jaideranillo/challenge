package com.cobre.challenge.adapter.out.webhook;

import com.cobre.challenge.application.port.out.webhook.WebhookEnvelopeSerializerPort;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes {@link WebhookEnvelope} with the shared, injected {@link ObjectMapper} (same bean
 * {@code SqsNotificationQueueAdapter} uses) — no mapper of its own, no pretty printing, no
 * mutation of the shared configuration.
 */
@Component
public class JacksonWebhookEnvelopeSerializer implements WebhookEnvelopeSerializerPort {

    private final ObjectMapper objectMapper;

    public JacksonWebhookEnvelopeSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(WebhookEnvelope envelope) {
        return objectMapper.writeValueAsString(envelope);
    }
}
