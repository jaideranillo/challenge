package com.cobre.challenge.adapter.out.webhook.dto;

import java.util.Objects;
import java.util.Optional;

/**
 * One egress decision for a single call. {@code reason} is short, non-PII, and safe to persist
 * as {@code delivery_attempts.error}: never the secret, a header, or the event content.
 * {@code resolvedAddress} is present only for a range-based {@code POLICY_REJECTED} (the first
 * offending address when several resolved); empty for a scheme rejection or a
 * {@code DNS_FAILURE}, where no address exists.
 */
public record EgressVerdict(State state, String reason, Optional<String> resolvedAddress) {

    public EgressVerdict {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(resolvedAddress, "resolvedAddress must not be null");
    }

    public static EgressVerdict allowed() {
        return new EgressVerdict(State.ALLOWED, "", Optional.empty());
    }

    public static EgressVerdict dnsFailure(String reason) {
        return new EgressVerdict(State.DNS_FAILURE, reason, Optional.empty());
    }

    public static EgressVerdict policyRejected(String reason) {
        return new EgressVerdict(State.POLICY_REJECTED, reason, Optional.empty());
    }

    public static EgressVerdict policyRejected(String reason, String resolvedAddress) {
        return new EgressVerdict(
                State.POLICY_REJECTED, reason, Optional.of(resolvedAddress));
    }

    /**
     * {@code DNS_FAILURE} (no answer obtained, transient, retryable) and {@code POLICY_REJECTED}
     * (an answer was obtained and the target is forbidden, permanent, not breaker-counting) are
     * kept distinct on purpose (TASK-007-20's {@code AttemptOutcome} classification depends on
     * telling them apart) and must never be collapsed into one "rejected" state.
     */
    public enum State {
        ALLOWED, DNS_FAILURE, POLICY_REJECTED
    }
}
