package com.cobre.challenge.adapter.in.web.selfservice.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code challenge.self-service.idempotency.*}: the replay idempotency guard's TTL and
 * bounded entry count. ADR-005 §1 gives no number for the TTL; the default below is a proposal,
 * the same caveat class as {@code RateLimitProperties}'s defaults.
 */
@Validated
@ConfigurationProperties("challenge.self-service.idempotency")
public record IdempotencyProperties(@DefaultValue("5m") Duration ttl, @DefaultValue("10000") @Positive int maxEntries) {
}
