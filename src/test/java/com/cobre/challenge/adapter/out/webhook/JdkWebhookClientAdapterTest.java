package com.cobre.challenge.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.out.webhook.config.WebhookHttpClientConfig;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;
import com.cobre.challenge.domain.policy.TransportFailure;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class JdkWebhookClientAdapterTest {

    private static final WorkerProperties WORKER_PROPERTIES = new WorkerProperties(
            true,
            Duration.ofSeconds(20),
            10,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            new WorkerProperties.Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
            new WorkerProperties.CircuitBreaker(10, Duration.ofSeconds(30), Duration.ofHours(1)),
            Duration.ofHours(1),
            1024);

    @Test
    void a200ResponseProducesNoneAndATruncatedExcerpt() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(fakeResponse(200, "0123456789", Map.of())).when(httpClient).send(any(), any());
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, withExcerptLimit(4));

        WebhookResponse response = adapter.send(webhookRequest("https://example.com/hook"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.failure()).isEqualTo(TransportFailure.NONE);
        assertThat(response.responseExcerpt()).contains("0123");
    }

    @Test
    void a500ResponseIsPassedThroughUnchanged() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(fakeResponse(500, "server error", Map.of())).when(httpClient).send(any(), any());
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, WORKER_PROPERTIES);

        WebhookResponse response = adapter.send(webhookRequest("https://example.com/hook"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.failure()).isEqualTo(TransportFailure.NONE);
    }

    @Test
    void aThrownHttpTimeoutExceptionProducesTimeoutAndDoesNotPropagate() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doThrow(new HttpTimeoutException("timed out")).when(httpClient).send(any(), any());
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, WORKER_PROPERTIES);

        WebhookResponse response = adapter.send(webhookRequest("https://example.com/hook"));

        assertThat(response.statusCode()).isEqualTo(0);
        assertThat(response.failure()).isEqualTo(TransportFailure.TIMEOUT);
        assertThat(response.responseExcerpt()).isEmpty();
        assertThat(response.retryAfter()).isEmpty();
    }

    @Test
    void theRequestBuiltCarriesEveryHeaderAndTheBodyVerbatim() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(fakeResponse(200, "", Map.of())).when(httpClient).send(any(), any());
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, WORKER_PROPERTIES);

        WebhookRequest request = new WebhookRequest(
                "https://example.com/hook",
                "{\"event\":\"x\"}",
                Map.of("X-Signature", "sig-value", "Content-Type", "application/json"));
        adapter.send(request);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpResponse.BodyHandler<String>> handlerCaptor = ArgumentCaptor.forClass(HttpResponse.BodyHandler.class);
        Mockito.verify(httpClient).send(captor.capture(), handlerCaptor.capture());
        HttpRequest sent = captor.getValue();

        assertThat(sent.uri().toString()).isEqualTo("https://example.com/hook");
        assertThat(sent.headers().firstValue("X-Signature")).contains("sig-value");
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(sent.bodyPublisher()).isPresent();
        assertThat(sent.bodyPublisher().get().contentLength())
                .isEqualTo("{\"event\":\"x\"}".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void theSharedClientIsConfiguredWithRedirectNever() {
        HttpClient client = new WebhookHttpClientConfig().webhookHttpClient(WORKER_PROPERTIES);

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).contains(WORKER_PROPERTIES.connectTimeout());
    }

    @Test
    void anHttpTargetIsSentUnchanged_theAdapterNoLongerRejectsItItself() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(fakeResponse(200, "", Map.of())).when(httpClient).send(any(), any());
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, WORKER_PROPERTIES);

        WebhookResponse response = adapter.send(webhookRequest("http://example.com/hook"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.failure()).isEqualTo(TransportFailure.NONE);
        Mockito.verify(httpClient).send(any(), any());
    }

    @Test
    void theAdapterNeverThrowsRegardlessOfTheUrlHandedToIt() {
        HttpClient httpClient = mock(HttpClient.class);
        JdkWebhookClientAdapter adapter = new JdkWebhookClientAdapter(httpClient, WORKER_PROPERTIES);

        WebhookResponse response = adapter.send(webhookRequest("http://[::1"));

        assertThat(response).isNotNull();
        assertThat(response.failure()).isNotEqualTo(TransportFailure.NONE);
    }

    private static WebhookRequest webhookRequest(String targetUrl) {
        return new WebhookRequest(targetUrl, "{}", Map.of("X-Signature", "sig"));
    }

    private static WorkerProperties withExcerptLimit(int limit) {
        return new WorkerProperties(
                WORKER_PROPERTIES.enabled(),
                WORKER_PROPERTIES.waitTime(),
                WORKER_PROPERTIES.batchSize(),
                WORKER_PROPERTIES.connectTimeout(),
                WORKER_PROPERTIES.readTimeout(),
                WORKER_PROPERTIES.bulkhead(),
                WORKER_PROPERTIES.circuitBreaker(),
                WORKER_PROPERTIES.retryAfterMax(),
                limit);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> fakeResponse(int statusCode, String body, Map<String, String> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(
                headers.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                (a, b) -> true));
        return response;
    }
}
