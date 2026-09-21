package com.cobre.challenge.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Resilience4jBulkheadAdapterTest {

	private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

	private final Resilience4jBulkheadAdapter adapter = new Resilience4jBulkheadAdapter();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void shutdown() {
		executor.shutdownNow();
	}

	@Test
	void secondAcquireReturnsFalseAfterTimeoutWhenPermitAlreadyHeld() throws Exception {
		UUID subscriptionId = UUID.randomUUID();
		CountDownLatch holderAcquired = new CountDownLatch(1);

		CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() -> {
			boolean acquired = adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT);
			holderAcquired.countDown();
			return acquired;
		}, executor);

		holderAcquired.await();
		assertThat(holder.get()).isTrue();

		boolean secondAcquired = adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT);

		assertThat(secondAcquired).isFalse();
	}

	@Test
	void acquireSucceedsAgainAfterRelease() {
		UUID subscriptionId = UUID.randomUUID();

		assertThat(adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT)).isTrue();
		adapter.release(subscriptionId);

		assertThat(adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT)).isTrue();
	}

	@Test
	void differentSubscriptionsDoNotContend() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertThat(adapter.tryAcquire(first, 1, SHORT_TIMEOUT)).isTrue();
		assertThat(adapter.tryAcquire(second, 1, SHORT_TIMEOUT)).isTrue();
	}

	@Test
	void changedMaxConcurrencyForExistingSubscriptionIsHonored() {
		UUID subscriptionId = UUID.randomUUID();

		assertThat(adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT)).isTrue();

		boolean secondUnderOldLimit = adapter.tryAcquire(subscriptionId, 1, SHORT_TIMEOUT);
		assertThat(secondUnderOldLimit).isFalse();

		boolean acquiredUnderRaisedLimit = adapter.tryAcquire(subscriptionId, 2, SHORT_TIMEOUT);
		assertThat(acquiredUnderRaisedLimit).isTrue();
	}
}
