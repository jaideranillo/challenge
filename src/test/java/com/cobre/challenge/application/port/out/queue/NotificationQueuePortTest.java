package com.cobre.challenge.application.port.out.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.queue.dto.PublishBatchResult;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void publishBatchResultRejectsNegativePublishedCount() {
        assertThatThrownBy(() -> new PublishBatchResult(-1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishBatchResultRejectsNullFailedDeliveryIds() {
        assertThatThrownBy(() -> new PublishBatchResult(0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void publishBatchResultDefensivelyCopiesFailedDeliveryIds() {
        List<UUID> mutableIds = new ArrayList<>(List.of(UUID.randomUUID()));
        PublishBatchResult result = new PublishBatchResult(1, mutableIds);

        mutableIds.add(UUID.randomUUID());

        assertThat(result.failedDeliveryIds()).hasSize(1);
    }

    @Test
    void publishBatchResultIsImmutable() {
        PublishBatchResult result = new PublishBatchResult(1, List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> result.failedDeliveryIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyBatchYieldsZeroPublishedAndEmptyFailedIdsNeverNull() {
        PublishBatchResult result = new PublishBatchResult(0, List.of());

        assertThat(result.publishedCount()).isZero();
        assertThat(result.failedDeliveryIds()).isEmpty();
    }
}
