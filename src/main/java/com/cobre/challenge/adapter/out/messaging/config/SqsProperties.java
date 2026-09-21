package com.cobre.challenge.adapter.out.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code challenge.sqs.*} keys already declared in {@code application-local.yaml}.
 *
 * <p>{@code endpoint} and {@code credentials} are absent outside the {@code local} profile: the
 * SDK then resolves the endpoint from {@code region} and the credentials from the IAM role chain
 * (ADR-002 SS1.1 Q10), which is the production path.
 */
@Validated
@ConfigurationProperties("challenge.sqs")
public record SqsProperties(
		Optional<String> endpoint, @NotBlank String region, Credentials credentials, @Valid @NotNull Queues queues) {

	public record Credentials(String accessKey, String secretKey) {
	}

	public record Queues(@NotBlank String deliveries, String deliveriesDlq) {
	}
}
