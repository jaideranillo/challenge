package com.cobre.challenge.adapter.in.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single hand-rolled token bucket (ADR-007 §6 — no Bucket4j, no Redis, no dependency, A03).
 * Capacity is the burst ceiling; tokens refill continuously at {@code refillTokens} per
 * {@code refillPeriod}, so a client draining the bucket faster than the sustained rate is
 * throttled smoothly rather than only once per period boundary.
 *
 * <p><strong>Virtual-thread safety:</strong> state transitions are a lock-free compare-and-set
 * loop over an immutable {@link State} — no {@code synchronized}, no blocking call anywhere in
 * this class, so a virtual thread calling {@link #tryConsume()} can never be pinned to its
 * carrier.
 *
 * <p>Time is injected via {@link Clock}; nothing here calls {@code Instant.now()} directly, so
 * refill behavior is exactly reproducible in tests with a fixed or manually-advanced clock.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillTokensPerNano;
    private final Clock clock;
    private final AtomicReference<State> state;

    public TokenBucket(long capacity, long refillTokens, Duration refillPeriod, Clock clock) {
        this.capacity = capacity;
        this.refillTokensPerNano = (double) refillTokens / refillPeriod.toNanos();
        this.clock = clock;
        this.state = new AtomicReference<>(new State(capacity, clock.instant()));
    }

    /** Attempts to consume one token; returns {@code true} if the bucket had one available. */
    public boolean tryConsume() {
        while (true) {
            State current = state.get();
            State refilled = refill(current);
            if (refilled.tokens() < 1.0) {
                state.compareAndSet(current, refilled);
                return false;
            }
            State next = new State(refilled.tokens() - 1.0, refilled.lastRefill());
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /** An approximate wait until the next token would be available; informational only (Retry-After). */
    public Duration timeUntilNextToken() {
        State refilled = refill(state.get());
        double deficit = 1.0 - refilled.tokens();
        if (deficit <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil(deficit / refillTokensPerNano));
    }

    private State refill(State current) {
        Instant now = clock.instant();
        long elapsedNanos = Duration.between(current.lastRefill(), now).toNanos();
        if (elapsedNanos <= 0) {
            return current;
        }
        double replenished = Math.min(capacity, current.tokens() + elapsedNanos * refillTokensPerNano);
        return new State(replenished, now);
    }

    private record State(double tokens, Instant lastRefill) {
    }
}
