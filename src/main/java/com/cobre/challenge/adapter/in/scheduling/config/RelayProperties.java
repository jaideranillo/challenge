package com.cobre.challenge.adapter.in.scheduling.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds {@code challenge.relay.*}; defaults are ADR-002 §2.1 values, not operational dials. */
@Validated
@ConfigurationProperties(prefix = "challenge.relay")
public record RelayProperties(boolean enabled, Duration pollInterval, @Positive int batchLimit) {
}
