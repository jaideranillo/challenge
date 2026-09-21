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

    private Delivery pending() {
        return new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void compactConstructorRejectsNullComponents() {
        assertThatThrownBy(() -> new Delivery(
                null, "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void compactConstructorRejectsNegativeAttemptCount() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                -1, Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compactConstructorRejectsDeliveredAtWithoutDeliveredStatus() {
        assertThatThrownBy(() -> new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST, Optional.empty(),
                0, Optional.empty(), Optional.empty(), Optional.of(Instant.now())))
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
    void markDeadThrowsFromIllegalSourceState() {
        Delivery pending = pending();
        assertThatThrownBy(() -> pending.markDead("err"))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }
}
