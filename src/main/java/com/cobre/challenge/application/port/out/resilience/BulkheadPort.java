package com.cobre.challenge.application.port.out.resilience;

import java.time.Duration;
import java.util.UUID;

/** Per-subscription in-process concurrency limiter (ADR-006 §1.3). */
public interface BulkheadPort {

	/** Acquires one permit for this subscription, waiting at most timeout. False means deferral. */
	boolean tryAcquire(UUID subscriptionId, int maxConcurrency, Duration timeout);

	/** Releases the permit acquired by this thread. Must be called from a finally block. */
	void release(UUID subscriptionId);
}
