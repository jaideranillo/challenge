package com.cobre.challenge.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Resilience4jCircuitBreakerAdapterTest {

	private static Resilience4jCircuitBreakerAdapter adapterWithThreshold(int failureThreshold) {
		WorkerProperties properties = new WorkerProperties(
				true,
				Duration.ofSeconds(20),
				10,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				new WorkerProperties.Bulkhead(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5)),
				new WorkerProperties.CircuitBreaker(failureThreshold, Duration.ofSeconds(10), Duration.ofSeconds(60)),
				Duration.ofSeconds(60),
				2048);
		return new Resilience4jCircuitBreakerAdapter(properties);
	}

	@Test
	void doesNotTripBeforeThreshold() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
	}

	@Test
	void tripsOnTheThresholdthFailure() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		adapter.recordFailure(subscriptionId);
		adapter.recordFailure(subscriptionId);
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = {10, 3})
	void tripsAtExactlyTheConfiguredThreshold(int failureThreshold) {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(failureThreshold);
		UUID subscriptionId = UUID.randomUUID();

		for (int i = 1; i < failureThreshold; i++) {
			assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		}
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();
	}

	@Test
	void furtherFailuresAfterTripReturnFalse() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		adapter.recordFailure(subscriptionId);
		adapter.recordFailure(subscriptionId);
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();

		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
	}

	@Test
	void successBeforeThresholdResetsTheCount() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		adapter.recordFailure(subscriptionId);
		adapter.recordFailure(subscriptionId);
		adapter.recordSuccess(subscriptionId);

		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();
	}

	@Test
	void resetIfOpenLocallyRestartsAnOpenBreakerFromZero() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		adapter.recordFailure(subscriptionId);
		adapter.recordFailure(subscriptionId);
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();

		adapter.resetIfOpenLocally(subscriptionId);

		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();
	}

	@Test
	void resetIfOpenLocallyOnAClosedUntouchedBreakerIsANoOp() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionId = UUID.randomUUID();

		assertThatCode(() -> adapter.resetIfOpenLocally(subscriptionId)).doesNotThrowAnyException();

		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isFalse();
		assertThat(adapter.recordFailure(subscriptionId)).isTrue();
	}

	@Test
	void keepsIndependentCountsPerSubscription() {
		Resilience4jCircuitBreakerAdapter adapter = adapterWithThreshold(3);
		UUID subscriptionA = UUID.randomUUID();
		UUID subscriptionB = UUID.randomUUID();

		adapter.recordFailure(subscriptionA);
		adapter.recordFailure(subscriptionA);
		assertThat(adapter.recordFailure(subscriptionA)).isTrue();

		assertThat(adapter.recordFailure(subscriptionB)).isFalse();
	}
}
