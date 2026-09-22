package com.cobre.challenge.adapter.out.webhook;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.out.webhook.WebhookClientPort;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.ResponseClassifier;
import com.cobre.challenge.domain.policy.TransportFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link WebhookClientPort}: one POST over the JDK {@link HttpClient},
 * strict timeouts, {@code Redirect.NEVER} on the shared client, never throws.
 *
 * <p>Callers must validate the target with {@code OutboundUrlValidator} before calling {@link
 * #send}; {@code AttemptDeliveryUseCaseImpl} is the only caller and is the only place that runs
 * that check (TASK-007-20).
 */
@Component
public class JdkWebhookClientAdapter implements WebhookClientPort {

    private static final String METRIC_ATTEMPT_LATENCY = "notification.delivery.attempt.latency";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_STATUS_CLASS = "status_class";

    private final HttpClient httpClient;
    private final WorkerProperties workerProperties;
    private final MeterRegistry meterRegistry;

    public JdkWebhookClientAdapter(HttpClient httpClient, WorkerProperties workerProperties, MeterRegistry meterRegistry) {
        this.httpClient = httpClient;
        this.workerProperties = workerProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public WebhookResponse send(WebhookRequest request) {
        Instant start = Instant.now();
        Timer.Sample sample = Timer.start(meterRegistry);
        WebhookResponse response;
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            response = toResponse(httpResponse, elapsedMs(start));
        } catch (Exception e) {
            response = new WebhookResponse(
                    0, WebhookResponseMapper.toTransportFailure(e), elapsedMs(start), Optional.empty(), 0,
                    Optional.empty());
        }
        sample.stop(timerFor(response));
        return response;
    }

    /**
     * ADR-008 §4.1: {@code outcome}/{@code status_class} only — no {@code delivery_id},
     * {@code client_id}, URL or raw status code (ADR-008 §4.3's cardinality rule). {@code
     * status_class} is the response status's first digit, "xx" for a transport failure with no
     * status code at all.
     */
    private Timer timerFor(WebhookResponse response) {
        AttemptOutcome outcome = response.failure() == TransportFailure.NONE
                ? ResponseClassifier.classify(response.statusCode(), response.failure())
                : AttemptOutcome.RETRYABLE;
        return Timer.builder(METRIC_ATTEMPT_LATENCY)
                .tag(TAG_OUTCOME, outcome.name())
                .tag(TAG_STATUS_CLASS, statusClassOf(response))
                .register(meterRegistry);
    }

    private static String statusClassOf(WebhookResponse response) {
        if (response.failure() != TransportFailure.NONE) {
            return "xx";
        }
        return (response.statusCode() / 100) + "xx";
    }

    private WebhookResponse toResponse(HttpResponse<String> httpResponse, int elapsedMs) {
        String body = httpResponse.body();
        int responseBodyLength = body != null ? body.length() : 0;
        Optional<String> excerpt = WebhookResponseMapper.truncate(body, workerProperties.responseExcerptLimit());
        Optional<Duration> retryAfter = WebhookResponseMapper.parseRetryAfter(
                httpResponse.headers().firstValue("Retry-After").orElse(null),
                Instant.now(),
                workerProperties.retryAfterMax());
        return new WebhookResponse(
                httpResponse.statusCode(), TransportFailure.NONE, elapsedMs, excerpt, responseBodyLength, retryAfter);
    }

    private HttpRequest buildRequest(WebhookRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.targetUrl()))
                .timeout(workerProperties.readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(request.body()));
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    private static int elapsedMs(Instant start) {
        return (int) Duration.between(start, Instant.now()).toMillis();
    }
}
