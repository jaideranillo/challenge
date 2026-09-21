package com.cobre.challenge.application.port.out.webhook;

import com.cobre.challenge.application.port.out.webhook.dto.WebhookEnvelope;

/**
 * Produces the exact string that is both HMAC-signed and sent as the webhook body (ADR-004
 * SS1.1). The use case must sign and send the same rendering, so serialization lives behind a
 * port rather than inside the HTTP adapter.
 */
public interface WebhookEnvelopeSerializerPort {

    String serialize(WebhookEnvelope envelope);
}
