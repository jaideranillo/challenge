package com.cobre.challenge.adapter.in.web.selfservice;

import com.cobre.challenge.adapter.in.web.selfservice.dto.ListNotificationEventsRequest;
import com.cobre.challenge.adapter.in.web.selfservice.dto.ListNotificationEventsResponse;
import com.cobre.challenge.adapter.in.web.selfservice.dto.NotificationEventDetailResponse;
import com.cobre.challenge.adapter.in.web.selfservice.dto.ReplayAcceptedResponse;
import com.cobre.challenge.application.port.in.selfservice.Accepted;
import com.cobre.challenge.application.port.in.selfservice.GetNotificationEventUseCase;
import com.cobre.challenge.application.port.in.selfservice.QueryNotificationEventsUseCase;
import com.cobre.challenge.application.port.in.selfservice.Rejected;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryResult;
import com.cobre.challenge.application.port.in.selfservice.ReplayDeliveryUseCase;
import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;
import com.cobre.challenge.application.port.in.selfservice.dto.ReplayDeliveryCommand;
import com.cobre.challenge.application.usecase.config.SelfServiceQueryProperties;
import com.cobre.challenge.domain.model.tenant.TenantId;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /notification_events} (ADR-005 §1): the self-service client API. HTTP in, command out,
 * result in, HTTP out — no business logic. {@code @PreAuthorize} here deliberately duplicates
 * {@code SecurityConfig}'s URL rule (ADR-007 §2): two cheap checks that fail independently.
 */
@RestController
@RequestMapping("/notification_events")
public class NotificationEventController {

    // Bounded length, printable ASCII only (ADR-005 §1): the header is client-supplied input
    // used downstream as a cache key (TASK-008-27), never trusted as free text.
    private static final Pattern IDEMPOTENCY_KEY_SHAPE = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private final QueryNotificationEventsUseCase queryUseCase;
    private final GetNotificationEventUseCase getUseCase;
    private final ReplayDeliveryUseCase replayUseCase;
    private final ReplayIdempotencyGuard idempotencyGuard;
    private final SelfServiceQueryProperties queryProperties;
    private final ReplaySpanRecorder replaySpanRecorder;

    public NotificationEventController(
            QueryNotificationEventsUseCase queryUseCase,
            GetNotificationEventUseCase getUseCase,
            ReplayDeliveryUseCase replayUseCase,
            ReplayIdempotencyGuard idempotencyGuard,
            SelfServiceQueryProperties queryProperties,
            ReplaySpanRecorder replaySpanRecorder) {
        this.queryUseCase = queryUseCase;
        this.getUseCase = getUseCase;
        this.replayUseCase = replayUseCase;
        this.idempotencyGuard = idempotencyGuard;
        this.queryProperties = queryProperties;
        this.replaySpanRecorder = replaySpanRecorder;
    }

    @PreAuthorize("hasAuthority('notifications:read')")
    @GetMapping
    public ResponseEntity<ListNotificationEventsResponse> list(
            @Valid @ModelAttribute ListNotificationEventsRequest request, TenantId tenant) {
        QueryNotificationEventsResult result =
                queryUseCase.query(request.toCommand(tenant, queryProperties.defaultPageSize()));
        return ResponseEntity.ok(ListNotificationEventsResponse.from(result));
    }

    @PreAuthorize("hasAuthority('notifications:read')")
    @GetMapping("/{notification_event_id}")
    public ResponseEntity<NotificationEventDetailResponse> get(
            @PathVariable("notification_event_id") UUID notificationEventId, TenantId tenant) {
        GetNotificationEventCommand command = new GetNotificationEventCommand(notificationEventId, tenant);
        return getUseCase
                .get(command)
                .map(NotificationEventDetailResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A missing/blank/malformed {@code Idempotency-Key} is a 400 before the guard or the use case
     * is ever consulted (ADR-005 §1); the header is required and never synthesized or defaulted
     * here. A valid key goes through {@link ReplayIdempotencyGuard} first — an immediate repeat
     * with the same {@code (tenant, deliveryId, idempotencyKey)} returns the first call's outcome
     * without invoking the use case again. The use-case result is mapped via an exhaustive switch
     * (Java 21 pattern matching) so a future {@link ReplayDeliveryResult} variant is a compile
     * error, not a silent wrong status.
     */
    @PreAuthorize("hasAuthority('notifications:replay')")
    @PostMapping("/{notification_event_id}/replay")
    public ResponseEntity<Object> replay(
            @PathVariable("notification_event_id") UUID notificationEventId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            TenantId tenant) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_SHAPE.matcher(idempotencyKey).matches()) {
            return ResponseEntity.badRequest().body(null);
        }

        AtomicReference<ReplayDeliveryResult> resultHolder = new AtomicReference<>();
        ResponseEntity<Object> response =
                idempotencyGuard.executeOrReplay(tenant, notificationEventId, idempotencyKey, () -> {
                    ReplayDeliveryCommand command =
                            new ReplayDeliveryCommand(notificationEventId, tenant, idempotencyKey);
                    ReplayDeliveryResult result = replayUseCase.replay(command);
                    resultHolder.set(result);
                    return switch (result) {
                        case Accepted accepted ->
                            ResponseEntity.status(HttpStatus.ACCEPTED).body(ReplayAcceptedResponse.from(accepted));
                        case Rejected rejected -> switch (rejected.reason()) {
                            case TARGET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
                            case TARGET_NOT_DEAD, LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS ->
                                ResponseEntity.status(HttpStatus.CONFLICT).body(null);
                        };
                    };
                });

        if (resultHolder.get() instanceof Accepted accepted) {
            replaySpanRecorder.record(notificationEventId, accepted.originalTraceContext());
        }
        return response;
    }
}
