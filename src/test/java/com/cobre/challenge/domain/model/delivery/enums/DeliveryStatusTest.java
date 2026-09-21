package com.cobre.challenge.domain.model.delivery.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeliveryStatusTest {

    @Test
    void hasExactlySevenStates() {
        assertThat(DeliveryStatus.values()).hasSize(7);
    }

    @Test
    void terminalStatesAreDeliveredDeadAndFailed() {
        Set<DeliveryStatus> terminal = EnumSet.of(
                DeliveryStatus.DELIVERED, DeliveryStatus.DEAD, DeliveryStatus.FAILED);

        for (DeliveryStatus status : DeliveryStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(terminal.contains(status));
        }
    }

    @Test
    void deliveryOriginHasExactlyThreeValues() {
        assertThat(DeliveryOrigin.values())
                .containsExactly(DeliveryOrigin.INGEST, DeliveryOrigin.REPLAY, DeliveryOrigin.RECOVERED);
    }
}
