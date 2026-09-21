package com.cobre.challenge.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.domain.policy.TransportFailure;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class WebhookResponseMapperTest {

    @Test
    void mapsTimeoutExceptions() {
        assertThat(WebhookResponseMapper.toTransportFailure(new HttpTimeoutException("timeout")))
                .isEqualTo(TransportFailure.TIMEOUT);
        assertThat(WebhookResponseMapper.toTransportFailure(new HttpConnectTimeoutException("timeout")))
                .isEqualTo(TransportFailure.TIMEOUT);
    }

    @Test
    void mapsTlsFailure() {
        assertThat(WebhookResponseMapper.toTransportFailure(new SSLException("bad cert")))
                .isEqualTo(TransportFailure.TLS_FAILURE);
    }

    @Test
    void mapsDnsFailure() {
        assertThat(WebhookResponseMapper.toTransportFailure(new UnknownHostException("no such host")))
                .isEqualTo(TransportFailure.DNS_FAILURE);
    }

    @Test
    void mapsUnrecognisedThrowableToConnectionReset() {
        assertThat(WebhookResponseMapper.toTransportFailure(new RuntimeException("boom")))
                .isEqualTo(TransportFailure.CONNECTION_RESET);
    }

    @Test
    void unwrapsCompletionExceptionBeforeClassifying() {
        CompletionException wrapped = new CompletionException(new HttpTimeoutException("timeout"));
        assertThat(WebhookResponseMapper.toTransportFailure(wrapped)).isEqualTo(TransportFailure.TIMEOUT);
    }

    @Test
    void truncateReturnsEmptyForNull() {
        assertThat(WebhookResponseMapper.truncate(null, 10)).isEmpty();
    }

    @Test
    void truncateReturnsEmptyForEmptyString() {
        assertThat(WebhookResponseMapper.truncate("", 10)).isEmpty();
    }

    @Test
    void truncateCapsAtLimit() {
        Optional<String> result = WebhookResponseMapper.truncate("0123456789ABCDEF", 5);
        assertThat(result).contains("01234");
    }

    @Test
    void parseRetryAfterAcceptsDeltaSeconds() {
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "120", Instant.parse("2026-10-21T00:00:00Z"), Duration.ofHours(1));
        assertThat(result).contains(Duration.ofSeconds(120));
    }

    @Test
    void parseRetryAfterAcceptsHttpDateInFuture() {
        Instant now = Instant.parse("2026-10-21T07:00:00Z");
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "Wed, 21 Oct 2026 07:28:00 GMT", now, Duration.ofHours(1));
        assertThat(result).contains(Duration.ofMinutes(28));
    }

    @Test
    void parseRetryAfterRejectsHttpDateInPast() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "Wed, 21 Oct 2026 07:28:00 GMT", now, Duration.ofHours(1));
        assertThat(result).isEmpty();
    }

    @Test
    void parseRetryAfterClampsDeltaSecondsAboveMax() {
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "7200", Instant.parse("2026-10-21T00:00:00Z"), Duration.ofHours(1));
        assertThat(result).contains(Duration.ofHours(1));
    }

    @Test
    void parseRetryAfterClampsHttpDateFarInFuture() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "Fri, 21 Oct 2033 00:00:00 GMT", now, Duration.ofHours(1));
        assertThat(result).contains(Duration.ofHours(1));
    }

    @Test
    void parseRetryAfterRejectsZero() {
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "0", Instant.parse("2026-10-21T00:00:00Z"), Duration.ofHours(1));
        assertThat(result).isEmpty();
    }

    @Test
    void parseRetryAfterRejectsNegative() {
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "-5", Instant.parse("2026-10-21T00:00:00Z"), Duration.ofHours(1));
        assertThat(result).isEmpty();
    }

    @Test
    void parseRetryAfterRejectsUnparseableGarbage() {
        Optional<Duration> result = WebhookResponseMapper.parseRetryAfter(
                "not-a-duration", Instant.parse("2026-10-21T00:00:00Z"), Duration.ofHours(1));
        assertThat(result).isEmpty();
    }

    @Test
    void parseRetryAfterNeverThrowsOnNullOrBlank() {
        assertThat(WebhookResponseMapper.parseRetryAfter(null, Instant.now(), Duration.ofHours(1))).isEmpty();
        assertThat(WebhookResponseMapper.parseRetryAfter("   ", Instant.now(), Duration.ofHours(1))).isEmpty();
    }
}
