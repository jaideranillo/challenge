package com.cobre.challenge.application.usecase.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code challenge.self-service.query.*} (ADR-005 §1, Amendment D2). Defaults are labelled
 * proposals, not measured numbers, in the same class as ADR-004's Q5 and ADR-006's Q7.
 */
@Validated
@ConfigurationProperties("challenge.self-service.query")
public record SelfServiceQueryProperties(
        @DefaultValue("50") @Positive int defaultPageSize,
        @DefaultValue("200") @Positive int maxPageSize,
        @DefaultValue("30d") Duration defaultWindow) {

    public SelfServiceQueryProperties {
        if (maxPageSize < defaultPageSize) {
            throw new IllegalArgumentException(
                    "maxPageSize must be >= defaultPageSize, was " + maxPageSize + " < " + defaultPageSize);
        }
    }
}
