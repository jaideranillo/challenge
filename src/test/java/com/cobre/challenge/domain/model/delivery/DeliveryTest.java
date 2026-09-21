package com.cobre.challenge.domain.model.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.delivery.exception.IllegalDeliveryTransitionException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryTest {

    private static final Instant EVENT_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Optional<String> TRACE_CONTEXT = Optional.of("00-abc-def-01");

    private Delivery pending() {
        return new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT_CREATED_AT, TRACE_CONTEXT);
    }

    @Test
    void compactConstructorRejectsNullComponents() {
        assertThatThrownBy(() -> new Delivery(
                null, "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT_CREATED_AT, TRACE_CONTEXT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void compactConstructorRejectsNullEventCreatedAt() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty(),
                null, TRACE_CONTEXT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventCreatedAt");
    }

    @Test
    void compactConstructorRejectsNullTraceContext() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT_CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("use Optional.empty()");
    }

    @Test
    void compactConstructorRejectsNegativeAttemptCount() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                -1, Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT_CREATED_AT, TRACE_CONTEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compactConstructorRejectsDeliveredAtWithoutDeliveredStatus() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.of(Instant.now()),
                EVENT_CREATED_AT, TRACE_CONTEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionToReturnsNewInstanceWithTargetStatus() {
        Delivery pending = pending();
        Delivery queued = pending.transitionTo(DeliveryStatus.QUEUED);

        assertThat(queued.status()).isEqualTo(DeliveryStatus.QUEUED);
        assertThat(pending.status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void transitionToPreservesEventCreatedAtAndTraceContext() {
        Delivery pending = pending();
        Delivery queued = pending.transitionTo(DeliveryStatus.QUEUED);

        assertThat(queued.eventCreatedAt()).isEqualTo(EVENT_CREATED_AT);
        assertThat(queued.traceContext()).isEqualTo(TRACE_CONTEXT);
    }

    @Test
    void transitionToThrowsOnIllegalTransition() {
        Delivery pending = pending();
        assertThatThrownBy(() -> pending.transitionTo(DeliveryStatus.DELIVERED))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void markDeliveredSetsDeliveredAtAndClearsNextAttemptAt() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);
        Instant now = Instant.now();

        Delivery delivered = processing.markDelivered(now);

        assertThat(delivered.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).contains(now);
        assertThat(delivered.nextAttemptAt()).isEmpty();
        assertThat(delivered.attemptCount()).isEqualTo(processing.attemptCount());
    }

    @Test
    void markDeliveredPreservesEventCreatedAtAndTraceContext() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);

        Delivery delivered = processing.markDelivered(Instant.now());

        assertThat(delivered.eventCreatedAt()).isEqualTo(EVENT_CREATED_AT);
        assertThat(delivered.traceContext()).isEqualTo(TRACE_CONTEXT);
    }

    @Test
    void markDeliveredThrowsFromIllegalSourceState() {
        Delivery pending = pending();
        assertThatThrownBy(() -> pending.markDelivered(Instant.now()))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void markRetryingIncrementsAttemptCountAndSetsFields() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);
        Instant next = Instant.now().plusSeconds(5);

        Delivery retrying = processing.markRetrying(next, "timeout");

        assertThat(retrying.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(retrying.attemptCount()).isEqualTo(processing.attemptCount() + 1);
        assertThat(retrying.nextAttemptAt()).contains(next);
        assertThat(retrying.lastError()).contains("timeout");
    }

    @Test
    void markRetryingPreservesEventCreatedAtAndTraceContext() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);

        Delivery retrying = processing.markRetrying(Instant.now().plusSeconds(5), "err");

        assertThat(retrying.eventCreatedAt()).isEqualTo(EVENT_CREATED_AT);
        assertThat(retrying.traceContext()).isEqualTo(TRACE_CONTEXT);
    }

    @Test
    void markRetryingThrowsFromIllegalSourceState() {
        Delivery pending = pending();
        assertThatThrownBy(() -> pending.markRetrying(Instant.now(), "err"))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void markDeadClearsNextAttemptAtAndLeavesAttemptCountUnchanged() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);

        Delivery dead = processing.markDead("404");

        assertThat(dead.status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(dead.nextAttemptAt()).isEmpty();
        assertThat(dead.lastError()).contains("404");
        assertThat(dead.attemptCount()).isEqualTo(processing.attemptCount());
    }

    @Test
    void markDeadPreservesEventCreatedAtAndTraceContext() {
        Delivery processing = pending()
                .transitionTo(DeliveryStatus.QUEUED)
                .transitionTo(DeliveryStatus.PROCESSING);

        Delivery dead = processing.markDead("err");

        assertThat(dead.eventCreatedAt()).isEqualTo(EVENT_CREATED_AT);
        assertThat(dead.traceContext()).isEqualTo(TRACE_CONTEXT);
    }

    @Test
    void markDeadThrowsFromIllegalSourceState() {
        Delivery pending = pending();
        assertThatThrownBy(() -> pending.markDead("err"))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }
}
