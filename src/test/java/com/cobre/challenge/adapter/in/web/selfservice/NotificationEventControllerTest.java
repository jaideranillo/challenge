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
import com.cobre.challenge.domain.model.delivery.enums.PublicDeliveryStatus;
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

    /**
     * Regression: a malformed {@code delivery_status} must be a clean 400 from inside this
     * handler, never left to Spring MVC's enum-binding machinery to throw
     * {@code MethodArgumentTypeMismatchException} - that path resolves via the servlet
     * container's internal {@code /error} forward, which re-enters Spring Security's filter
     * chain, matches no client-API chain, and falls through to the terminal {@code denyAll()},
     * producing a stray 403. Same failure class already fixed for the
     * {@code notification_event_id} path variable.
     */
    @Test
    void listReturns400ForAMalformedDeliveryStatus_notForbidden() {
        ListNotificationEventsRequest request = new ListNotificationEventsRequest(
                Optional.empty(), Optional.empty(), Optional.of("bogus"), Optional.empty(), 50);

        ResponseEntity<ListNotificationEventsResponse> response = controller.list(request, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(queryUseCase);
    }

    /** Regression: the internal enum name is not a valid wire value (ADR-003 §1.1). */
    @Test
    void listReturns400WhenGivenTheInternalEnumNameInsteadOfThePublicVocabulary() {
        ListNotificationEventsRequest request = new ListNotificationEventsRequest(
                Optional.empty(), Optional.empty(), Optional.of("DELIVERED"), Optional.empty(), 50);

        ResponseEntity<ListNotificationEventsResponse> response = controller.list(request, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(queryUseCase);
    }

    @Test
    void listAcceptsThePublicVocabularyAndExpandsItToInternalStatesForTheCommand() {
        when(queryUseCase.query(any(QueryNotificationEventsCommand.class)))
                .thenReturn(new QueryNotificationEventsResult(List.of(), Optional.empty()));
        ListNotificationEventsRequest request = new ListNotificationEventsRequest(
                Optional.empty(), Optional.empty(), Optional.of("pending"), Optional.empty(), 50);

        ResponseEntity<ListNotificationEventsResponse> response = controller.list(request, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(queryUseCase).query(new QueryNotificationEventsCommand(
                TENANT, Optional.empty(), Optional.empty(), Optional.of(PublicDeliveryStatus.PENDING),
                Optional.empty(), 50));
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
                Optional.of(from), Optional.of(to), Optional.of("completed"), Optional.of("cur-1"), 50);

        controller.list(request, TENANT);

        verify(queryUseCase)
                .query(new QueryNotificationEventsCommand(
                        TENANT, Optional.of(from), Optional.of(to), Optional.of(PublicDeliveryStatus.COMPLETED),
                        Optional.of("cur-1"), 50));
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

        ResponseEntity<NotificationEventDetailResponse> response = controller.get(deliveryId.toString(), TENANT);

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

        ResponseEntity<NotificationEventDetailResponse> response = controller.get(deliveryId.toString(), TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    /**
     * Regression: a malformed id must read as "not found" (404), never as "forbidden" (403).
     * Before this fix, {@code @PathVariable UUID} binding failed inside Spring MVC's argument
     * resolution (before this method ran), and the servlet container's internal error dispatch
     * for that failure re-entered Spring Security's filter chain against {@code /error} - which
     * matches no client-API chain and falls through to the terminal {@code denyAll()} - producing
     * a stray 403. See ADR-007 §5.5: a foreign or malformed id must never be distinguishable from
     * a well-formed nonexistent one.
     */
    @Test
    void getReturns404ForAMalformedId_notForbidden() {
        ResponseEntity<NotificationEventDetailResponse> response = controller.get("not-a-uuid", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(getUseCase);
    }

    @Test
    void replayReturns404ForAMalformedId_notForbidden() {
        ResponseEntity<Object> response = controller.replay("not-a-uuid", "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(replayUseCase);
    }

    @Test
    void getBuildsTheCommandWithTheResolvedTenant() {
        UUID deliveryId = UUID.randomUUID();
        when(getUseCase.get(any(GetNotificationEventCommand.class))).thenReturn(Optional.empty());

        controller.get(deliveryId.toString(), TENANT);

        verify(getUseCase).get(new GetNotificationEventCommand(deliveryId, TENANT));
    }

    @Test
    void replayReturns202WithTheNewDeliveryIdAndPendingWhenAccepted() {
        UUID originalId = UUID.randomUUID();
        UUID newDeliveryId = UUID.randomUUID();
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Accepted(newDeliveryId, DeliveryStatus.PENDING, Optional.empty()));

        ResponseEntity<Object> response = controller.replay(originalId.toString(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ReplayAcceptedResponse body = (ReplayAcceptedResponse) response.getBody();
        assertThat(body.deliveryId()).isEqualTo(newDeliveryId);
        assertThat(body.status()).isEqualTo(PublicDeliveryStatus.PENDING);
    }

    @Test
    void replayReturns404WhenTheTargetIsNotFound() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_FOUND));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void replayReturns409WhenTheTargetIsNotDead() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_DEAD));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void replayReturns409WhenALiveOrDeliveredRowAlreadyExists() {
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS));

        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), "idem-key-1", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsMissing() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), null, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(replayUseCase);
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsBlank() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), "   ", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(replayUseCase, never()).replay(any());
    }

    @Test
    void replayReturns400AndDoesNotCallTheUseCaseWhenTheHeaderIsMalformed() {
        ResponseEntity<Object> response = controller.replay(UUID.randomUUID().toString(), "bad key\nwith control chars", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(replayUseCase, never()).replay(any());
    }

    @Test
    void replayBuildsTheCommandWithTheResolvedTenantAndTheGivenKey() {
        UUID deliveryId = UUID.randomUUID();
        when(replayUseCase.replay(any(ReplayDeliveryCommand.class)))
                .thenReturn(new Rejected(RejectionReason.TARGET_NOT_FOUND));

        controller.replay(deliveryId.toString(), "idem-key-1", TENANT);

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
