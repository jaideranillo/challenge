package com.cobre.challenge.application.port.out.webhook.dto;

import com.cobre.challenge.domain.policy.Redaction;
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
        int responseBodyLength,
        Optional<Duration> retryAfter) {

    public WebhookResponse {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(responseExcerpt, "responseExcerpt must not be null (use Optional.empty())");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null (use Optional.empty())");
        if (responseBodyLength < 0) {
            throw new IllegalArgumentException("responseBodyLength must not be negative");
        }
    }

    /** ADR-008 §3.3: never print {@code responseExcerpt} — a redaction marker and its length instead. */
    @Override
    public String toString() {
        return "WebhookResponse[statusCode=%d, failure=%s, responseTimeMs=%d, responseExcerpt=%s, responseBodyLength=%d, retryAfter=%s]"
                .formatted(
                        statusCode, failure, responseTimeMs, Redaction.redact(responseExcerpt), responseBodyLength,
                        retryAfter);
    }
}
