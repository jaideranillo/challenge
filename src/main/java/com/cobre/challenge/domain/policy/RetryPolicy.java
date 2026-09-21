package com.cobre.challenge.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The retry backoff schedule of ADR-004 SS1 / ADR-006 SS1: 5s, 30s, 2m, 10m,
 * 1h, 6h, each with +/-20% jitter. Framework-free and deterministic under an
 * injected {@link RandomGenerator}.
 *
 * Three points not stated by the ADRs, fixed here as labelled implementation
 * choices (see FEAT-003's "Open item flagged" section):
 * 1. Jitter is a uniform draw over [0.8 x nominal, 1.2 x nominal].
 * 2. Randomness is injected via {@link RandomGenerator} rather than a static source.
 * 3. max_attempts is the schedule length (six); no seventh interval is invented.
 */
public final class RetryPolicy {

    private static final double JITTER_LOWER_FACTOR = 0.8;
    private static final double JITTER_UPPER_FACTOR = 1.2;

    private final List<Duration> schedule;
    private final RandomGenerator random;

    public RetryPolicy(List<Duration> schedule, RandomGenerator random) {
        if (schedule == null || schedule.isEmpty()) {
            throw new IllegalArgumentException("schedule must not be null or empty");
        }
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }

        Duration previous = null;
        for (Duration duration : schedule) {
            if (duration == null) {
                throw new IllegalArgumentException("schedule must not contain null entries");
            }
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("schedule entries must be positive, was " + duration);
            }
            if (previous != null && duration.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("schedule must be strictly increasing");
            }
            previous = duration;
        }

        this.schedule = List.copyOf(schedule);
        this.random = random;
    }

    public static RetryPolicy defaultSchedule(RandomGenerator random) {
        return new RetryPolicy(
                List.of(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1),
                        Duration.ofHours(6)),
                random);
    }

    public int maxAttempts() {
        return schedule.size();
    }

    public Optional<Duration> nextBackoff(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > schedule.size()) {
            return Optional.empty();
        }

        Duration nominal = schedule.get(attemptNumber - 1);
        double factor = JITTER_LOWER_FACTOR + random.nextDouble() * (JITTER_UPPER_FACTOR - JITTER_LOWER_FACTOR);
        long jitteredMillis = Math.round(nominal.toMillis() * factor);
        return Optional.of(Duration.ofMillis(jitteredMillis));
    }

    public Optional<Instant> nextAttemptAt(int attemptNumber, Instant now) {
        return nextBackoff(attemptNumber).map(now::plus);
    }
}
