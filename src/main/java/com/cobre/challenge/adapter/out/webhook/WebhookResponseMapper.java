package com.cobre.challenge.adapter.out.webhook;

import com.cobre.challenge.domain.policy.TransportFailure;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * Pure, stateless helpers for turning raw HTTP-adapter results into domain-safe values.
 */
public final class WebhookResponseMapper {

    private WebhookResponseMapper() {
    }

    /** Classifies a transport-level exception into a {@link TransportFailure}, never {@code NONE}. */
    public static TransportFailure toTransportFailure(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException) {
            return TransportFailure.TIMEOUT;
        }
        if (cause instanceof SSLException) {
            return TransportFailure.TLS_FAILURE;
        }
        if (cause instanceof UnknownHostException) {
            return TransportFailure.DNS_FAILURE;
        }
        return TransportFailure.CONNECTION_RESET;
    }

    private static Throwable unwrap(Throwable throwable) {
        if ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    /** Truncates a response body to at most {@code limit} characters, empty for null/blank input. */
    public static Optional<String> truncate(String body, int limit) {
        if (body == null || body.isEmpty()) {
            return Optional.empty();
        }
        if (body.length() > limit) {
            return Optional.of(body.substring(0, limit));
        }
        return Optional.of(body);
    }

    /** Parses a {@code Retry-After} header (delta-seconds or RFC 7231 HTTP-date), clamped to {@code max}. */
    public static Optional<Duration> parseRetryAfter(String headerValue, Instant now, Duration max) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }
        String trimmed = headerValue.trim();

        Optional<Duration> deltaSeconds = parseDeltaSeconds(trimmed);
        Duration duration = deltaSeconds.orElseGet(() -> parseHttpDate(trimmed, now).orElse(null));
        if (duration == null) {
            return Optional.empty();
        }
        if (duration.isZero() || duration.isNegative()) {
            return Optional.empty();
        }
        return Optional.of(duration.compareTo(max) > 0 ? max : duration);
    }

    private static Optional<Duration> parseDeltaSeconds(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseHttpDate(String value, Instant now) {
        try {
            Instant target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(Duration.between(now, target));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
