package com.cobre.challenge.application.port.out.webhook.dto;

import com.cobre.challenge.domain.policy.TransportFailure;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral: feeds {@code ResponseClassifier.classify(int, TransportFailure)}
 * directly. No {@code HttpResponse}, no {@code ResponseEntity}, no client-library
 * exception type crosses this boundary. {@code responseExcerpt} is already truncated
 * by the adapter and must never contain signature headers or secrets (ADR-003 SS3, A09).
 */
public record WebhookResponse(
        int statusCode,
        TransportFailure failure,
        int responseTimeMs,
        Optional<String> responseExcerpt,
        Optional<Duration> retryAfter) {

    public WebhookResponse {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(responseExcerpt, "responseExcerpt must not be null (use Optional.empty())");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null (use Optional.empty())");
    }
}
