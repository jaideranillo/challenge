package com.cobre.challenge.adapter.in.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds {@code challenge.worker.*}; defaults are ADR-002/ADR-004/ADR-006 values (see feature.md). */
@Validated
@ConfigurationProperties("challenge.worker")
public record WorkerProperties(
		boolean enabled,
		Duration waitTime,
		@Positive int batchSize,
		Duration connectTimeout,
		Duration readTimeout,
		@Valid @NotNull Bulkhead bulkhead,
		@Valid @NotNull CircuitBreaker circuitBreaker,
		Duration retryAfterMax,
		@Positive int responseExcerptLimit) {

	public record Bulkhead(Duration acquireTimeout, Duration deferMin, Duration deferMax) {
	}

	public record CircuitBreaker(@Positive int failureThreshold, Duration baseCooldown, Duration maxCooldown) {
	}
}
