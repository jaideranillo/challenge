package com.cobre.challenge.adapter.in.web.security.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code challenge.security.jwt.*} (ADR-007 §3): this service's own audience and the
 * maximum token lifetime it enforces regardless of what the IdP does. {@code maxLifetime} is a
 * labelled proposal (default 1h), the same class as ADR-004's Q5 and ADR-006's Q7.
 */
@Validated
@ConfigurationProperties("challenge.security.jwt")
public record JwtProperties(@NotBlank String audience, @DefaultValue("1h") Duration maxLifetime) {
}
