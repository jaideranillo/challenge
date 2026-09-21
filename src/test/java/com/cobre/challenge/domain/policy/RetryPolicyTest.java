package com.cobre.challenge.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final List<Duration> NOMINAL = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6));

    /** Stubbed generator whose nextDouble() draws are supplied up front. */
    private static RandomGenerator fixed(double... draws) {
        return new RandomGenerator() {
            private int index = 0;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return draws[index++ % draws.length];
            }
        };
    }

    @Test
    void jitterStaysWithinBoundForEveryAttempt() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            Duration nominal = NOMINAL.get(attempt - 1);
            Duration lower = Duration.ofMillis(Math.round(nominal.toMillis() * 0.8));
            Duration upper = Duration.ofMillis(Math.round(nominal.toMillis() * 1.2));

            for (long seed = 0; seed < 1000; seed++) {
                RetryPolicy policy = RetryPolicy.defaultSchedule(java.util.random.RandomGeneratorFactory
                        .of("L64X128MixRandom").create(seed));
                Duration actual = policy.nextBackoff(attempt).orElseThrow();
                assertThat(actual).isBetween(lower, upper);
            }
        }
    }

    @Test
    void jitterIsActuallyApplied() {
        java.util.Set<Duration> observed = new java.util.HashSet<>();
        for (long seed = 0; seed < 1000; seed++) {
            RetryPolicy policy = RetryPolicy.defaultSchedule(java.util.random.RandomGeneratorFactory
                    .of("L64X128MixRandom").create(seed));
            observed.add(policy.nextBackoff(1).orElseThrow());
        }
        assertThat(observed).as("jitter must produce more than one distinct value").hasSizeGreaterThan(1);
    }

    @Test
    void backoffIsMonotonicAcrossAttemptsForEverySeed() {
        for (long seed = 0; seed < 1000; seed++) {
            RetryPolicy policy = RetryPolicy.defaultSchedule(java.util.random.RandomGeneratorFactory
                    .of("L64X128MixRandom").create(seed));
            List<Duration> values = new ArrayList<>();
            for (int attempt = 1; attempt <= 6; attempt++) {
                values.add(policy.nextBackoff(attempt).orElseThrow());
            }
            for (int i = 0; i < values.size() - 1; i++) {
                assertThat(values.get(i))
                        .as("seed %d: nextBackoff(%d) must be < nextBackoff(%d); schedule or jitter factor changed",
                                seed, i + 1, i + 2)
                        .isLessThan(values.get(i + 1));
            }
        }
    }

    @Test
    void midpointDrawReturnsExactNominalSchedule() {
        RetryPolicy policy = new RetryPolicy(NOMINAL, fixed(0.5));

        assertThat(policy.nextBackoff(1)).contains(Duration.ofSeconds(5));
        assertThat(policy.nextBackoff(2)).contains(Duration.ofSeconds(30));
        assertThat(policy.nextBackoff(3)).contains(Duration.ofMinutes(2));
        assertThat(policy.nextBackoff(4)).contains(Duration.ofMinutes(10));
        assertThat(policy.nextBackoff(5)).contains(Duration.ofHours(1));
        assertThat(policy.nextBackoff(6)).contains(Duration.ofHours(6));
    }

    @Test
    void exhaustionReturnsEmptyNeverNullNeverThrows() {
        RetryPolicy policy = new RetryPolicy(NOMINAL, fixed(0.5));

        assertThat(policy.nextBackoff(6)).isPresent();
        assertThat(policy.nextBackoff(7)).isEmpty();
        assertThat(policy.nextBackoff(100)).isEmpty();
        assertThat(policy.maxAttempts()).isEqualTo(6);
    }

    @Test
    void samePolicyIsDeterministicAcrossRuns() {
        RandomGenerator random1 = java.util.random.RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        RandomGenerator random2 = java.util.random.RandomGeneratorFactory.of("L64X128MixRandom").create(42L);

        RetryPolicy policy1 = RetryPolicy.defaultSchedule(random1);
        RetryPolicy policy2 = RetryPolicy.defaultSchedule(random2);

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(policy1.nextBackoff(attempt)).isEqualTo(policy2.nextBackoff(attempt));
        }
    }

    @Test
    void constructorRejectsInvalidSchedules() {
        RandomGenerator random = fixed(0.5);

        assertThatThrownBy(() -> new RetryPolicy(null, random)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(List.of(), random)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Arrays.asList(Duration.ofSeconds(1), null), random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(List.of(Duration.ofSeconds(0)), random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(List.of(Duration.ofSeconds(-1)), random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(List.of(Duration.ofSeconds(30), Duration.ofSeconds(5)), random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextAttemptAtAddsJitteredDurationToSuppliedInstantAndEmptyOnExhaustion() {
        RetryPolicy policy = new RetryPolicy(NOMINAL, fixed(0.5));
        Instant now = Instant.parse("2026-09-20T00:00:00Z");

        Optional<Instant> attemptOne = policy.nextAttemptAt(1, now);
        assertThat(attemptOne).contains(now.plus(Duration.ofSeconds(5)));

        assertThat(policy.nextAttemptAt(7, now)).isEmpty();
    }
}
