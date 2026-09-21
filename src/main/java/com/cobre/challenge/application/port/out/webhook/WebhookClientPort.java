package com.cobre.challenge.application.port.out.webhook;

import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;

/**
 * The outbound POST to a client's webhook target (ADR-004 SS1). No
 * {@code followRedirects} option exists: 3xx is terminal by design. HMAC
 * signing happens before the call reaches this port (ADR-004 SS2); the
 * request record carries an already-computed signature header value.
 */
public interface WebhookClientPort {

    WebhookResponse send(WebhookRequest request);
}
