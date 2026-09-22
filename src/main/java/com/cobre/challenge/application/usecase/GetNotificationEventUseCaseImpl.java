package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.in.selfservice.GetNotificationEventUseCase;
import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.NotificationEventQueryRepositoryPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /notification_events/{notification_event_id}} (ADR-005 §1, ADR-007 §5.5).
 *
 * <p>The path variable carries the delivery row's {@code UUID}, not the platform's own event id:
 * step 1 resolves the {@link Delivery} by that id, step 2 reads the event body via
 * {@code delivery.eventId()} (a {@code String} business key), step 3 reads the full attempt
 * history by the same delivery id. A foreign or nonexistent id is indistinguishable at step 1 —
 * both yield {@link Optional#empty()} with no further read (404, never 403).
 */
@Service
public class GetNotificationEventUseCaseImpl implements GetNotificationEventUseCase {

    private final DeliveryQueryRepositoryPort deliveryQueryRepository;
    private final NotificationEventQueryRepositoryPort notificationEventQueryRepository;
    private final DeliveryAttemptQueryRepositoryPort deliveryAttemptQueryRepository;

    public GetNotificationEventUseCaseImpl(
            DeliveryQueryRepositoryPort deliveryQueryRepository,
            NotificationEventQueryRepositoryPort notificationEventQueryRepository,
            DeliveryAttemptQueryRepositoryPort deliveryAttemptQueryRepository) {
        this.deliveryQueryRepository = deliveryQueryRepository;
        this.notificationEventQueryRepository = notificationEventQueryRepository;
        this.deliveryAttemptQueryRepository = deliveryAttemptQueryRepository;
    }

    @Override
    @Transactional(transactionManager = "apiTransactionManager", readOnly = true)
    public Optional<NotificationEventDetail> get(GetNotificationEventCommand command) {
        Optional<Delivery> maybeDelivery =
                deliveryQueryRepository.findById(command.deliveryId(), command.tenant());
        if (maybeDelivery.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = maybeDelivery.get();

        NotificationEvent notificationEvent = notificationEventQueryRepository
                .findById(delivery.eventId(), command.tenant())
                .orElseThrow(() -> new IllegalStateException(
                        "delivery row has no matching notification_events row"));

        List<DeliveryAttempt> attempts =
                deliveryAttemptQueryRepository.findByDeliveryId(command.deliveryId(), command.tenant());

        return Optional.of(new NotificationEventDetail(delivery, notificationEvent, attempts));
    }
}
