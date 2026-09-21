package com.cobre.challenge.adapter.in.messaging.config;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds {@code challenge.retry.backoff}; the schedule fed into the {@link com.cobre.challenge.domain.policy.RetryPolicy} bean. */
@Validated
@ConfigurationProperties("challenge.retry")
public record RetryProperties(@NotEmpty List<Duration> backoff) {
}
