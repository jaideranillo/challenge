package com.cobre.challenge.domain.model.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryAttemptTest {

    @Test
    void compactConstructorRejectsNullDeliveryId() {
        assertThatThrownBy(() -> new DeliveryAttempt(
                null, 1, OptionalInt.of(200), 120, Optional.empty(), Optional.empty(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void compactConstructorRejectsNullOptionalFields() {
        assertThatThrownBy(() -> new DeliveryAttempt(
                UUID.randomUUID(), 1, null, 120, Optional.empty(), Optional.empty(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void httpStatusMayBeAbsentForTransportFailures() {
        DeliveryAttempt attempt = new DeliveryAttempt(
                UUID.randomUUID(), 1, OptionalInt.empty(), 5000,
                Optional.empty(), Optional.of("timeout"), Instant.now());

        assertThat(attempt.httpStatus()).isEmpty();
        assertThat(attempt.error()).contains("timeout");
    }
}
