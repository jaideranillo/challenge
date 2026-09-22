package com.cobre.challenge.adapter.in.messaging.config;

import com.cobre.challenge.domain.policy.RetryPolicy;
import java.time.Clock;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Constructs the one {@link RetryPolicy} bean from {@link RetryProperties}, plus the process-wide
 * {@link Clock} and {@link RandomGenerator} beans that {@code AttemptDeliveryUseCaseImpl} needs.
 *
 * <p>{@code RandomGenerator.getDefault()} is not used here: its javadoc documents the returned
 * algorithm as unsafe for concurrent use from multiple threads without splitting, and this bean is
 * shared by thousands of virtual threads. {@code RandomGeneratorFactory.of("Random")} wraps the
 * legacy {@link java.util.Random}, which is thread-safe (internally CAS-guarded) and is not a
 * cryptographic generator, matching the "jitter, not a security control" requirement.
 */
@Configuration
@EnableConfigurationProperties(RetryProperties.class)
public class RetryPolicyConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public RandomGenerator randomGenerator() {
		return RandomGeneratorFactory.of("Random").create();
	}

	@Bean
	public RetryPolicy retryPolicy(RetryProperties properties, RandomGenerator randomGenerator) {
		return new RetryPolicy(properties.backoff(), randomGenerator);
	}
}
