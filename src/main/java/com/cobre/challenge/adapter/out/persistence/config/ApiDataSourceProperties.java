package com.cobre.challenge.adapter.out.persistence.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code challenge.security.api-datasource.*}: the {@code challenge_api} role's
 * credentials (ADR-007 §5.4). The URL is deliberately not bound here - it is derived
 * from the primary {@code DataSource} in {@link ApiDataSourceConfig} (TASK-008-09).
 */
@Validated
@ConfigurationProperties("challenge.security.api-datasource")
public record ApiDataSourceProperties(@NotBlank String username, @NotBlank String password) {
}
