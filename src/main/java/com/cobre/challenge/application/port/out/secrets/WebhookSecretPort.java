package com.cobre.challenge.application.port.out.secrets;

import java.util.Optional;

/**
 * Resolves a {@code subscriptions.secret_ref} to HMAC secret material (ADR-004 §2, §3).
 *
 * <p>The reference is never the secret. This port returns material only; no method exposes the
 * backing map, lists references, or reports how many secrets are configured, since that would
 * turn a resolver into an enumeration surface.
 */
public interface WebhookSecretPort {

	/**
	 * Resolves {@code secretRef} to its secret material.
	 *
	 * <p>{@link Optional#empty()} for an unknown, null, or blank reference. Never throws: an
	 * unknown reference is an operational condition the use case must handle as a non-retryable
	 * failure, not a crash (OWASP A10).
	 */
	Optional<String> resolve(String secretRef);
}
