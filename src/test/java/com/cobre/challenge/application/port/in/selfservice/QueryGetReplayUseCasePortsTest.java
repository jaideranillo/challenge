package com.cobre.challenge.application.port.in.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.RejectionReason;
import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryGetReplayUseCasePortsTest {

    @Test
    void queryCommandRequiresTenant() {
        assertThatThrownBy(() -> new QueryNotificationEventsCommand(
                null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void queryCommandStatusFilterIsTypedNotString() {
        QueryNotificationEventsCommand command = new QueryNotificationEventsCommand(
                new TenantId("client-1"), Optional.empty(), Optional.empty(),
                Optional.of(DeliveryStatus.PENDING), Optional.empty(), 50);
        assertThat(command.status()).contains(DeliveryStatus.PENDING);
    }

    @Test
    void queryCommandExposesEventCreatedFromAndTo() {
        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now();
        QueryNotificationEventsCommand command = new QueryNotificationEventsCommand(
                new TenantId("client-1"), Optional.of(from), Optional.of(to),
                Optional.empty(), Optional.empty(), 50);
        assertThat(command.eventCreatedFrom()).contains(from);
        assertThat(command.eventCreatedTo()).contains(to);
    }

    @Test
    void queryCommandRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new QueryNotificationEventsCommand(
                new TenantId("client-1"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getCommandRequiresBothDeliveryIdAndTenant() {
        assertThatThrownBy(() -> new GetNotificationEventCommand(null, new TenantId("client-1")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GetNotificationEventCommand(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void notificationEventDetailCarriesDeliveryEventAndAttemptHistory() {
        Delivery delivery = new Delivery(
                UUID.randomUUID(), "EVT001", UUID.randomUUID(), "client-1",
                DeliveryStatus.DELIVERED, DeliveryOrigin.INGEST, Optional.empty(),
                1, Optional.empty(), Optional.empty(), Optional.of(Instant.now()),
                Instant.now(), Optional.empty());
        NotificationEvent event = new NotificationEvent(
                "EVT001", "client-1", "payment.created", "{}", Instant.now());

        NotificationEventDetail detail = new NotificationEventDetail(delivery, event, List.of());

        assertThat(detail.delivery()).isEqualTo(delivery);
        assertThat(detail.notificationEvent()).isEqualTo(event);
        assertThat(detail.attempts()).isEmpty();
    }

    @Test
    void replayCommandRequiresIdempotencyKey() {
        assertThatThrownBy(() -> new ReplayDeliveryCommand(UUID.randomUUID(), new TenantId("client-1"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replayCommandRequiresTenant() {
        assertThatThrownBy(() -> new ReplayDeliveryCommand(UUID.randomUUID(), null, "idem-1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replayResultAcceptedCarriesNewDeliveryIdAndPendingStatus() {
        UUID newId = UUID.randomUUID();
        ReplayDeliveryResult result = new Accepted(newId, DeliveryStatus.PENDING);

        assertThat(result).isInstanceOf(Accepted.class);
        assertThat(((Accepted) result).newDeliveryId()).isEqualTo(newId);
        assertThat(((Accepted) result).status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void replayResultRejectedCarriesTypedReason() {
        ReplayDeliveryResult result = new Rejected(RejectionReason.TARGET_NOT_DEAD);

        assertThat(result).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result).reason()).isEqualTo(RejectionReason.TARGET_NOT_DEAD);
    }
}
