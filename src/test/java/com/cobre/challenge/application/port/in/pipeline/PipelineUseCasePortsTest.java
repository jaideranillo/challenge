package com.cobre.challenge.application.port.in.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PipelineUseCasePortsTest {

    @Test
    void registerNotificationEventCommandCompiles() {
        RegisterNotificationEventCommand command = new RegisterNotificationEventCommand(
                "EVT001", "client-1", "payment.created", "{}", Instant.now());
        assertThat(command.eventId()).isEqualTo("EVT001");
    }

    @Test
    void registerNotificationEventResultDefaultsEmptyListWhenNoSubscriptionMatched() {
        RegisterNotificationEventResult result = new RegisterNotificationEventResult(List.of(), true);
        assertThat(result.deliveryIds()).isEmpty();
    }

    @Test
    void registerNotificationEventResultRejectsNullDeliveryIds() {
        assertThatThrownBy(() -> new RegisterNotificationEventResult(null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dispatchPendingDeliveriesCommandRejectsNonPositiveBatchLimit() {
        assertThatThrownBy(() -> new DispatchPendingDeliveriesCommand(0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatchPendingDeliveriesResultCompiles() {
        DispatchPendingDeliveriesResult result = new DispatchPendingDeliveriesResult(3, 3);
        assertThat(result.claimedCount()).isEqualTo(3);
    }

    @Test
    void attemptDeliveryCommandCarriesFourPointerFields() {
        AttemptDeliveryCommand command = new AttemptDeliveryCommand(
                UUID.randomUUID(), UUID.randomUUID(), 1, Optional.of("00-trace-01"));
        assertThat(command.traceparent()).isPresent();
    }

    @Test
    void attemptDeliveryResultCarriesStatusAndOutcome() {
        AttemptDeliveryResult result = AttemptDeliveryResult.of(DeliveryStatus.DELIVERED, AttemptOutcome.SUCCESS);
        assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(result.outcome()).contains(AttemptOutcome.SUCCESS);
    }
}
