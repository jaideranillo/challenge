package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.in.selfservice.QueryNotificationEventsUseCase;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.application.usecase.config.SelfServiceQueryProperties;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.delivery.enums.PublicDeliveryStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /notification_events} (ADR-005 §1, Amendment D2): clamps the page size and applies
 * the default 30-day window only when the caller supplies neither date bound.
 */
@Service
@EnableConfigurationProperties(SelfServiceQueryProperties.class)
public class QueryNotificationEventsUseCaseImpl implements QueryNotificationEventsUseCase {

    private final DeliveryQueryRepositoryPort deliveryQueryRepository;
    private final SelfServiceQueryProperties properties;
    private final Clock clock;

    public QueryNotificationEventsUseCaseImpl(
            DeliveryQueryRepositoryPort deliveryQueryRepository,
            SelfServiceQueryProperties properties,
            Clock clock) {
        this.deliveryQueryRepository = deliveryQueryRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(transactionManager = "apiTransactionManager", readOnly = true)
    public QueryNotificationEventsResult query(QueryNotificationEventsCommand command) {
        int limit = clampLimit(command.limit());
        DeliveryPageQuery pageQuery = withDefaultWindow(command);

        DeliveryPage page = deliveryQueryRepository.findPage(command.tenant(), pageQuery, limit);

        return new QueryNotificationEventsResult(page.deliveries(), page.nextCursor());
    }

    // command.limit() is validated positive by QueryNotificationEventsCommand's own compact
    // constructor, so <= 0 cannot arrive here today; the guard is kept because ADR-005 §1's rule
    // is stated as "an absent limit becomes the default" and this is the only place it can apply.
    private int clampLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return properties.defaultPageSize();
        }
        return Math.min(requestedLimit, properties.maxPageSize());
    }

    private DeliveryPageQuery withDefaultWindow(QueryNotificationEventsCommand command) {
        Optional<Instant> from = command.eventCreatedFrom();
        Optional<Instant> to = command.eventCreatedTo();

        if (from.isEmpty() && to.isEmpty()) {
            Instant now = clock.instant();
            from = Optional.of(now.minus(properties.defaultWindow()));
            to = Optional.of(now);
        }

        Set<DeliveryStatus> statuses = command.status().map(PublicDeliveryStatus::internalStates).orElse(Set.of());
        return new DeliveryPageQuery(from, to, statuses, command.cursor());
    }
}
