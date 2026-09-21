package com.cobre.challenge.adapter.out.resilience;

import com.cobre.challenge.application.port.out.resilience.BulkheadPort;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One {@link Bulkhead} per {@code subscription_id}, held in-process (ADR-006 §1.3). Never reads
 * the database and holds no repository; callers supply {@code maxConcurrency} and {@code
 * timeout} from the already-loaded subscription row.
 */
@Component
public class Resilience4jBulkheadAdapter implements BulkheadPort {

	private final ConcurrentHashMap<UUID, Bulkhead> bulkheads = new ConcurrentHashMap<>();

	@Override
	public boolean tryAcquire(UUID subscriptionId, int maxConcurrency, Duration timeout) {
		Bulkhead bulkhead = bulkheadFor(subscriptionId, maxConcurrency, timeout);
		return bulkhead.tryAcquirePermission();
	}

	@Override
	public void release(UUID subscriptionId) {
		Bulkhead bulkhead = bulkheads.get(subscriptionId);
		if (bulkhead != null) {
			bulkhead.releasePermission();
		}
	}

	private Bulkhead bulkheadFor(UUID subscriptionId, int maxConcurrency, Duration timeout) {
		return bulkheads.compute(subscriptionId, (id, existing) -> {
			if (existing != null && matches(existing, maxConcurrency, timeout)) {
				return existing;
			}
			return Bulkhead.of(id.toString(), BulkheadConfig.custom()
					.maxConcurrentCalls(maxConcurrency)
					.maxWaitDuration(timeout)
					.build());
		});
	}

	private boolean matches(Bulkhead bulkhead, int maxConcurrency, Duration timeout) {
		BulkheadConfig config = bulkhead.getBulkheadConfig();
		return config.getMaxConcurrentCalls() == maxConcurrency && config.getMaxWaitDuration().equals(timeout);
	}
}
