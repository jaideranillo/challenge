package com.cobre.challenge.application.port.out.webhook.dto;

import java.util.Map;
import java.util.Objects;

public record WebhookRequest(String targetUrl, String body, Map<String, String> headers) {

    public WebhookRequest {
        Objects.requireNonNull(targetUrl, "targetUrl must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        headers = Map.copyOf(headers);
    }
}
