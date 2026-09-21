package com.cobre.challenge.adapter.out.webhook.config;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared {@link HttpClient} bean for the process: the JDK client is thread-safe and its
 * blocking {@code send} unmounts a virtual thread correctly, so no per-call client and no
 * executor of its own. {@code Redirect.NEVER} per ADR-004 SS1 (3xx is terminal, never followed).
 */
@Configuration
public class WebhookHttpClientConfig {

    @Bean
    public HttpClient webhookHttpClient(WorkerProperties workerProperties) {
        return HttpClient.newBuilder()
                .connectTimeout(workerProperties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
