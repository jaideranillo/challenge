package com.cobre.challenge.adapter.in.web.selfservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.adapter.in.web.selfservice.config.IdempotencyProperties;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ReplayIdempotencyGuardTest {

    private static final TenantId TENANT = new TenantId("client-1");
    private static final TenantId OTHER_TENANT = new TenantId("client-2");
    private static final Instant START = Instant.parse("2026-09-21T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    void firstCallPassesThrough() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<Object> response = guard.executeOrReplay(
                TENANT, UUID.randomUUID(), "key-1", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getBody()).isEqualTo("call-1");
    }

    @Test
    void immediateRepeatReturnsTheCachedOutcomeAndDoesNotCallTheAction() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();
        UUID deliveryId = UUID.randomUUID();

        ResponseEntity<Object> first = guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));
        ResponseEntity<Object> second = guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void aDifferentKeyForTheSameDeliveryPassesThrough() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();
        UUID deliveryId = UUID.randomUUID();

        guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));
        guard.executeOrReplay(TENANT, deliveryId, "key-2", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void theSameKeyForADifferentTenantPassesThrough() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();
        UUID deliveryId = UUID.randomUUID();

        guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));
        guard.executeOrReplay(OTHER_TENANT, deliveryId, "key-1", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void theSameKeyForADifferentDeliveryPassesThrough() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();

        guard.executeOrReplay(TENANT, UUID.randomUUID(), "key-1", () -> accepted(calls));
        guard.executeOrReplay(TENANT, UUID.randomUUID(), "key-1", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void afterTheTtlExpiresTheSameKeyReachesTheActionAgain() {
        ReplayIdempotencyGuard guard = guardWithTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();
        UUID deliveryId = UUID.randomUUID();

        guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        guard.executeOrReplay(TENANT, deliveryId, "key-1", () -> accepted(calls));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void theMapEvictsTheLeastRecentlyUsedEntryOnceTheMaximumIsExceeded() {
        IdempotencyProperties properties = new IdempotencyProperties(Duration.ofMinutes(5), 2);
        ReplayIdempotencyGuard guard = new ReplayIdempotencyGuard(properties, clock);
        AtomicInteger calls = new AtomicInteger();

        UUID delivery1 = UUID.randomUUID();
        UUID delivery2 = UUID.randomUUID();
        UUID delivery3 = UUID.randomUUID();

        guard.executeOrReplay(TENANT, delivery1, "key-1", () -> accepted(calls));
        guard.executeOrReplay(TENANT, delivery2, "key-1", () -> accepted(calls));
        assertThat(guard.trackedEntryCount()).isEqualTo(2);

        guard.executeOrReplay(TENANT, delivery3, "key-1", () -> accepted(calls));
        assertThat(guard.trackedEntryCount()).isEqualTo(2);

        // delivery1 was the least recently used and is now evicted: it reaches the action again.
        int callsBeforeRepeat = calls.get();
        guard.executeOrReplay(TENANT, delivery1, "key-1", () -> accepted(calls));
        assertThat(calls.get()).isEqualTo(callsBeforeRepeat + 1);
    }

    private ReplayIdempotencyGuard guardWithTtl(Duration ttl) {
        return new ReplayIdempotencyGuard(new IdempotencyProperties(ttl, 10_000), clock);
    }

    private static ResponseEntity<Object> accepted(AtomicInteger calls) {
        int callNumber = calls.incrementAndGet();
        return ResponseEntity.accepted().body("call-" + callNumber);
    }

    /** Test-only {@link Clock} that can be advanced manually; no test ever sleeps. */
    private static final class MutableClock extends Clock {
        private final AtomicLong epochMillis;

        MutableClock(Instant start) {
            this.epochMillis = new AtomicLong(start.toEpochMilli());
        }

        void advance(Duration duration) {
            epochMillis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis.get());
        }
    }
}
