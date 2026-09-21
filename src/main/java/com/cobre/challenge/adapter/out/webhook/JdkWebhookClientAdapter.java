package com.cobre.challenge.adapter.out.webhook;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.out.webhook.WebhookClientPort;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;
import com.cobre.challenge.domain.policy.TransportFailure;
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

    private final HttpClient httpClient;
    private final WorkerProperties workerProperties;

    public JdkWebhookClientAdapter(HttpClient httpClient, WorkerProperties workerProperties) {
        this.httpClient = httpClient;
        this.workerProperties = workerProperties;
    }

    @Override
    public WebhookResponse send(WebhookRequest request) {
        Instant start = Instant.now();
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return toResponse(httpResponse, elapsedMs(start));
        } catch (Exception e) {
            return new WebhookResponse(
                    0, WebhookResponseMapper.toTransportFailure(e), elapsedMs(start), Optional.empty(), Optional.empty());
        }
    }

    private WebhookResponse toResponse(HttpResponse<String> httpResponse, int elapsedMs) {
        Optional<String> excerpt =
                WebhookResponseMapper.truncate(httpResponse.body(), workerProperties.responseExcerptLimit());
        Optional<Duration> retryAfter = WebhookResponseMapper.parseRetryAfter(
                httpResponse.headers().firstValue("Retry-After").orElse(null),
                Instant.now(),
                workerProperties.retryAfterMax());
        return new WebhookResponse(httpResponse.statusCode(), TransportFailure.NONE, elapsedMs, excerpt, retryAfter);
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
