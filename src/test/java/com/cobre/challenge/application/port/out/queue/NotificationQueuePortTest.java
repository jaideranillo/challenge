package com.cobre.challenge.application.port.out.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationQueuePortTest {

    @Test
    void deliveryPointerRejectsNullDeliveryId() {
        assertThatThrownBy(() -> new DeliveryPointer(null, UUID.randomUUID(), 1, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliveryPointerCarriesExactlyFourScalarFields() {
        DeliveryPointer pointer = new DeliveryPointer(
                UUID.randomUUID(), UUID.randomUUID(), 2, Optional.of("00-trace-01"));

        assertThat(pointer.getClass().getRecordComponents()).hasSize(4);
    }
}
