package com.cobre.challenge.domain.model.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationEventTest {

    @Test
    void compactConstructorRejectsNullEventId() {
        assertThatThrownBy(() -> new NotificationEvent(null, "client-1", "payment.created", "{}", Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
