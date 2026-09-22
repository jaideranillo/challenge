package com.cobre.challenge.adapter.in.web.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code challenge.security.rate-limit.*} (ADR-007 §6): per-client read and replay
 * budgets enforced by {@code ClientRateLimitFilter}. All defaults here are labelled proposals,
 * not derived from measured usage — the same caveat class as ADR-004's Q5 and ADR-006's Q7.
 */
@Validated
@ConfigurationProperties("challenge.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("600") int readRequestsPerMinute,
        @DefaultValue("60") int readBurst,
        @DefaultValue("10") int replayRequestsPerMinute,
        @DefaultValue("200") int replayRequestsPerDay,
        @DefaultValue("5") int replayBurst,
        @DefaultValue("10000") int maxTrackedClients) {
}
