package com.cobre.challenge.adapter.in.web.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.web.selfservice.dto.ListNotificationEventsRequest;
import com.cobre.challenge.adapter.in.web.selfservice.dto.ListNotificationEventsResponse;
import com.cobre.challenge.adapter.in.web.selfservice.dto.NotificationEventDetailResponse;
import com.cobre.challenge.adapter.in.web.selfservice.config.IdempotencyProperties;
import com.cobre.challenge.adapter.in.web.selfservice.dto.ReplayAcceptedResponse;
import com.cobre.challenge.application.port.in.selfservice.Accepted;
import com.cobre.challenge.application.port.in.selfservice.GetNotificationEventUseCase;
import com.cobre.challenge.application.port.in.selfservice.QueryNotificationEventsUseCase;
import com.cobre.challenge.application.port.in.selfservice.Rejected;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryResult;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryUseCase;
import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;
import com.cobre.challenge.application.port.in.selfservice.dto.RejectionReason;
import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;
import com.cobre.challenge.application.usecase.config.SelfServiceQueryProperties;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class NotificationEventControllerTest {

    private static final TenantId TENANT = new TenantId("client-1");
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private final QueryNotificationEventsUseCase queryUseCase = mock(QueryNotificationEventsUseCase.class);
    private final GetNotificationEventUseCase getUseCase = mock(GetNotificationEventUseCase.class);
    private final ReplayDeliveryUseCase replayUseCase = mock(ReplayDeliveryUseCase.class);
    // A real (non-mocked) guard: TASK-008-26's replay-mapping tests each use a distinct
    // deliveryId/idempotencyKey pair, so a cache miss (pass-through) is exercised every time.
    // The guard's own caching behavior is covered by ReplayIdempotencyGuardTest.
    private final ReplayIdempotencyGuard idempotencyGuard = new ReplayIdempotencyGuard(
            new IdempotencyProperties(Duration.ofMinutes(5), 10_000), Clock.fixed(NOW, ZoneOffset.UTC));

    private final ReplaySpanRecorder replaySpanRecorder = mock(ReplaySpanRecorder.class);

    private final NotificationEventController controller = new NotificationEventController(
            queryUseCase,
            getUseCase,
            replayUseCase,
            idempotencyGuard,
            new SelfServiceQueryProperties(50, 200, Duration.ofDays(30)),
            replaySpanRecorder);

    @Test
    void listMapsTheResultToTheResponseBody() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = delivery(deliveryId);
        when(queryUseCase.query(any(QueryNotificationEventsCommand.class)))
                .thenReturn(new QueryNotificationEventsResult(List.of(delivery), Optional.of("next-cursor")));

        ListNotificationEventsRequest request =
                new ListNotificationEventsRequest(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50);
        ResponseEntity<ListNotificationEventsResponse> response = controller.list(request, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items()).hasSize(1);
        assertThat(response.getBody().items().get(0).deliveryId()).isEqualTo(deliveryId);
        assertThat(response.getBody().nextCursor()).contains("next-cursor");
    }

    @Test
    void listHasNoNextCursorOnTheLastPage() {
        when(queryUseCase.query(any(QueryNotificationEventsCommand.class)))
                .thenReturn(new QueryNotificationEventsResult(List.of(), Optional.empty()));

        ListNotificationEventsRequest request =
                new ListNotificationEventsRequest(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50);
        ResponseEntity<ListNotificationEventsResponse> response = controller.list(request, TENANT);

        assertThat(response.getBody().nextCursor()).isEmpty();
    }

    @Test
    void listBuildsTheCommandWithTheResolvedTenantAndTheMappedFilterNames() {
        when(queryUseCase.query(any(QueryNotificationEventsCommand.class)))
                .thenReturn(new QueryNotificationEventsResult(List.of(), Optional.empty()));

        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-21T00:00:00Z");
        ListNotificationEventsRequest request = new ListNotificationEventsRequest(
                Optional.of(from), Optional.of(to), Optional.of(DeliveryStatus.DELIVERED), Optional.of("cur-1"), 50);

        controller.list(request, TENANT);

        verify(queryUseCase)
                .query(new QueryNotificationEventsCommand(
                        TENANT, Optional.of(from), Optional.of(to), Optional.of(DeliveryStatus.DELIVERED), Optional.of("cur-1"), 50));
    }

    @Test
    void listPassesAnOversizedLimitThroughUntouched() {
        when(queryUseCase.query(any(QueryNotificationEventsCommand.class)))
                .thenReturn(new QueryNotificationEventsResult(List.of(), Optional.empty()));

        ListNotificationEventsRequest request =
                new ListNotificationEventsRequest(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 10_000);

        controller.list(request, TENANT);

        verify(queryUseCase)
                .query(new QueryNotificationEventsCommand(
                        TENANT, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 10_000));
    }

    @Test
    void getMapsTheResultToTheResponseBodyIncludingEveryAttempt() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = delivery(deliveryId);
        NotificationEvent event = new NotificationEvent("EVT001", "client-1", "payment.created", "{}", NOW);
        DeliveryAttempt attempt1 = attempt(deliveryId, 1);
        DeliveryAttempt attempt2 = attempt(deliveryId, 2);
        when(getUseCase.get(new GetNotificationEventCommand(deliveryId, TENANT)))
                .thenReturn(Optional.of(new NotificationEventDetail(delivery, event, List.of(attempt1, attempt2))));

        ResponseEntity<NotificationEventDetailResponse> response = controller.get(deliveryId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().delivery().deliveryId()).isEqualTo(deliveryId);
        assertThat(response.getBody().eventId()).isEqualTo("EVT001");
        assertThat(response.getBody().eventType()).isEqualTo("payment.created");
        assertThat(response.getBody().content()).isEqualTo("{}");
        assertThat(response.getBody().attempts()).hasSize(2);
    }

    @Test
    void getReturns404WhenTheUseCaseReturnsEmpty() {
        UUID deliveryId = UUID.randomUUID();
        when(getUseCase.get(new GetNotificationEventCommand(deliveryId, TENANT))).thenReturn(Optional.empty());

        ResponseEntity<NotificationEventDetailResponse> response = controller.get(deliveryId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void getBuildsTheCommandWithTheResolvedTenant() {
        UUID deliveryId = UUID.randomUUID();
        when(getUseCase.get(any(GetNotificationEventCommand.class))).thenReturn(Optional.empty());

        controller.get(deliveryId, TENANT);

        verify(getUseCase).get(new GetNotificationEventCommand(deliveryId, TENANT));
    }

    @Test
    void replayReturns202WithTheNewDeliveryIdAndPendingWhenAccepted() {
        UUID originalId = UUID.randomUUID();
        UUID newDeliveryId = UUID.randomUUID();
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Accepted(newDeliveryId, DeliveryStatus.PENDING, Optional.empty()));

        ResponseEntity<Object> response = controller.replay(originalId, "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ReplayAcceptedResponse body = (ReplayAcceptedResponse) response.getBody();
        assertThat(body.deliveryId()).isEqualTo(newDeliveryId);
        assertThat(body.status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void replayReturns404WhenTheTargetIsNotFound() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_FOUND));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void replayReturns409WhenTheTargetIsNotDead() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_DEAD));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void replayReturns409WhenALiveOrDeliveredRowAlreadyExists() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsMissing() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), null, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(replayUseCase);
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsBlank() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), "   ", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(replayUseCase, never()).replay(any());
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsMalformed() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID(), "bad key\nwith control chars", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(replayUseCase, never()).replay(any());
    }

    @Test
    void replayBuildsTheCommandWithTheResolvedTenantAndTheGivenKey() {
        UUID deliveryId = UUID.randomUUID();
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_FOUND));

        controller.replay(deliveryId, "idem-key-1", TENANT);

        verify(replayUseCase).replay(new ReplayDeliveryCommand(deliveryId, TENANT, "idem-key-1"));
    }

    private static Delivery delivery(UUID deliveryId) {
        return new Delivery(
                deliveryId,
                "EVT001",
                UUID.randomUUID(),
                "client-1",
                DeliveryStatus.DELIVERED,
                DeliveryOrigin.INGEST,
                Optional.empty(),
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW),
                NOW,
                Optional.of("traceparent-should-not-leak"));
    }

    private static DeliveryAttempt attempt(UUID deliveryId, int attemptNumber) {
        return new DeliveryAttempt(
                deliveryId, attemptNumber, OptionalInt.of(200), 120, Optional.of("OK"), Optional.empty(), NOW);
    }
}
