package com.cobre.challenge.domain.model.delivery.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR-003 §1's public/internal {@code delivery_status} mapping. Plain JUnit, no Spring context.
 */
class PublicDeliveryStatusTest {

    @Test
    void internalStatesPartitionAllSevenInternalStatesWithNoOverlapAndNoGap() {
        Set<DeliveryStatus> union = new HashSet<>();
        for (PublicDeliveryStatus value : PublicDeliveryStatus.values()) {
            for (DeliveryStatus internal : value.internalStates()) {
                assertThat(union.add(internal))
                        .as(internal + " claimed by more than one public status").isTrue();
            }
        }
        assertThat(union).isEqualTo(EnumSet.allOf(DeliveryStatus.class));
    }

    @Test
    void pendingCoversTheFourInFlightStates() {
        assertThat(PublicDeliveryStatus.PENDING.internalStates()).containsExactlyInAnyOrder(
                DeliveryStatus.PENDING, DeliveryStatus.QUEUED, DeliveryStatus.PROCESSING, DeliveryStatus.RETRYING);
    }

    @Test
    void completedIsDeliveredOnly() {
        assertThat(PublicDeliveryStatus.COMPLETED.internalStates()).containsExactly(DeliveryStatus.DELIVERED);
    }

    @Test
    void failedCoversDeadAndFailed() {
        assertThat(PublicDeliveryStatus.FAILED.internalStates())
                .containsExactlyInAnyOrder(DeliveryStatus.DEAD, DeliveryStatus.FAILED);
    }

    @Test
    void fromWireParsesTheSampleFileValues() {
        assertThat(PublicDeliveryStatus.fromWire("pending")).contains(PublicDeliveryStatus.PENDING);
        assertThat(PublicDeliveryStatus.fromWire("completed")).contains(PublicDeliveryStatus.COMPLETED);
        assertThat(PublicDeliveryStatus.fromWire("failed")).contains(PublicDeliveryStatus.FAILED);
    }

    @Test
    void fromWireIsCaseInsensitive() {
        assertThat(PublicDeliveryStatus.fromWire("COMPLETED")).contains(PublicDeliveryStatus.COMPLETED);
        assertThat(PublicDeliveryStatus.fromWire("Completed")).contains(PublicDeliveryStatus.COMPLETED);
    }

    @Test
    void fromWireReturnsEmptyForAnInternalEnumNameOrGarbage() {
        // The internal enum name must never work as a wire value — only the public vocabulary
        // does, per ADR-003 §1.1's "never leaking the domain enum".
        assertThat(PublicDeliveryStatus.fromWire("DELIVERED")).isEmpty();
        assertThat(PublicDeliveryStatus.fromWire("bogus")).isEmpty();
        assertThat(PublicDeliveryStatus.fromWire(null)).isEmpty();
    }

    @Test
    void ofMapsEveryInternalStateBackToItsPublicValue() {
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.PENDING)).isEqualTo(PublicDeliveryStatus.PENDING);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.QUEUED)).isEqualTo(PublicDeliveryStatus.PENDING);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.PROCESSING)).isEqualTo(PublicDeliveryStatus.PENDING);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.RETRYING)).isEqualTo(PublicDeliveryStatus.PENDING);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.DELIVERED)).isEqualTo(PublicDeliveryStatus.COMPLETED);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.DEAD)).isEqualTo(PublicDeliveryStatus.FAILED);
        assertThat(PublicDeliveryStatus.of(DeliveryStatus.FAILED)).isEqualTo(PublicDeliveryStatus.FAILED);
    }
}
