package com.cobre.challenge.adapter.in.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** Plain JUnit, no Spring context: bean construction and validation delegate straight to RetryPolicy. */
class RetryPolicyConfigTest {

	private final RetryPolicyConfig config = new RetryPolicyConfig();
	private final RandomGenerator randomGenerator = config.randomGenerator();

	@Test
	void buildsPolicyWithMaxAttemptsEqualToScheduleSize() {
		RetryProperties properties = new RetryProperties(
				List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)));

		var policy = config.retryPolicy(properties, randomGenerator);

		assertThat(policy.maxAttempts()).isEqualTo(3);
	}

	@Test
	void rejectsEmptyBackoffList() {
		RetryProperties properties = new RetryProperties(List.of());

		assertThatThrownBy(() -> config.retryPolicy(properties, randomGenerator))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void clockIsUtc() {
		assertThat(config.clock().getZone()).isEqualTo(java.time.ZoneOffset.UTC);
	}

}
