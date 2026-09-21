package com.cobre.challenge.application.port.out.resilience;

import java.util.UUID;

/** Pod-local, in-memory failure counting for the per-subscription breaker (ADR-006 §1.2). */
public interface CircuitBreakerPort {

	/** A breaker-counting success (ADR-004 §1's classification). Resets the local count. */
	void recordSuccess(UUID subscriptionId);

	/** A breaker-counting failure. @return true only on the transition, never on later failures. */
	boolean recordFailure(UUID subscriptionId);

	/** ADR-006 §1.2's last bullet: the database says CLOSED, so clear this pod's local state. */
	void resetIfOpenLocally(UUID subscriptionId);
}
