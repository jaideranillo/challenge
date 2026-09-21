package com.cobre.challenge.adapter.out.resilience;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.out.resilience.CircuitBreakerPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * One {@link CircuitBreaker} per {@code subscription_id}, held in-process (ADR-006 §1.2). Never
 * reads or writes the database and holds no repository; the conditional {@code subscriptions}
 * write belongs to the use case, driven off {@link #recordFailure}'s return value.
 */
@Component
public class Resilience4jCircuitBreakerAdapter implements CircuitBreakerPort {

	// Automatic open-to-half-open is disabled below; this duration is never awaited on.
	private static final Duration INERT_WAIT_DURATION = Duration.ofDays(365);

	// Reused as the onError() cause; never thrown, logged, or carries subscription data.
	private static final Throwable FAILURE_MARKER = new IllegalStateException("breaker-counting failure");

	private final WorkerProperties workerProperties;
	private final ConcurrentHashMap<UUID, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	public Resilience4jCircuitBreakerAdapter(WorkerProperties workerProperties) {
		this.workerProperties = workerProperties;
	}

	@Override
	public void recordSuccess(UUID subscriptionId) {
		breakerFor(subscriptionId).onSuccess(0, TimeUnit.NANOSECONDS);
	}

	@Override
	public boolean recordFailure(UUID subscriptionId) {
		CircuitBreaker breaker = breakerFor(subscriptionId);
		CircuitBreaker.State before = breaker.getState();
		breaker.onError(0, TimeUnit.NANOSECONDS, FAILURE_MARKER);
		return before != CircuitBreaker.State.OPEN && breaker.getState() == CircuitBreaker.State.OPEN;
	}

	@Override
	public void resetIfOpenLocally(UUID subscriptionId) {
		CircuitBreaker breaker = breakers.get(subscriptionId);
		if (breaker != null && breaker.getState() == CircuitBreaker.State.OPEN) {
			breaker.reset();
		}
	}

	private CircuitBreaker breakerFor(UUID subscriptionId) {
		return breakers.computeIfAbsent(subscriptionId, id -> CircuitBreaker.of(id.toString(), buildConfig()));
	}

	private CircuitBreakerConfig buildConfig() {
		int failureThreshold = workerProperties.circuitBreaker().failureThreshold();
		return CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(failureThreshold)
				.minimumNumberOfCalls(failureThreshold)
				.failureRateThreshold(100)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				.waitDurationInOpenState(INERT_WAIT_DURATION)
				.build();
	}
}
