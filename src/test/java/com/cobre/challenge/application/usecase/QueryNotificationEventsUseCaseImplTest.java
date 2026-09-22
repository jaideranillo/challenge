package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.QueryNotificationEventsResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.application.usecase.config.SelfServiceQueryProperties;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryNotificationEventsUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final TenantId TENANT = new TenantId("client-1");

    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SelfServiceQueryProperties properties =
            new SelfServiceQueryProperties(50, 200, Duration.ofDays(30));
    private final FakeDeliveryQueryRepository repository = new FakeDeliveryQueryRepository();

    private final QueryNotificationEventsUseCaseImpl useCase =
            new QueryNotificationEventsUseCaseImpl(repository, properties, fixedClock);

    @Test
    void limitAboveMaxIsClampedToMax() {
        useCase.query(command(500, Optional.empty(), Optional.empty()));

        assertThat(repository.lastLimit).isEqualTo(200);
    }

    @Test
    void limitAtMaxIsPassedThrough() {
        useCase.query(command(200, Optional.empty(), Optional.empty()));

        assertThat(repository.lastLimit).isEqualTo(200);
    }

    @Test
    void limitBelowMaxIsPassedThroughUnchanged() {
        useCase.query(command(10, Optional.empty(), Optional.empty()));

        assertThat(repository.lastLimit).isEqualTo(10);
    }

    @Test
    void bothBoundsAbsentAppliesDefault30DayWindowEndingNow() {
        useCase.query(command(50, Optional.empty(), Optional.empty()));

        assertThat(repository.lastQuery.eventCreatedFrom()).contains(NOW.minus(Duration.ofDays(30)));
        assertThat(repository.lastQuery.eventCreatedTo()).contains(NOW);
    }

    @Test
    void onlyFromSuppliedDoesNotInjectADefaultTo() {
        Instant from = NOW.minus(Duration.ofDays(5));

        useCase.query(command(50, Optional.of(from), Optional.empty()));

        assertThat(repository.lastQuery.eventCreatedFrom()).contains(from);
        assertThat(repository.lastQuery.eventCreatedTo()).isEmpty();
    }

    @Test
    void onlyToSuppliedDoesNotInjectADefaultFrom() {
        Instant to = NOW.minus(Duration.ofDays(1));

        useCase.query(command(50, Optional.empty(), Optional.of(to)));

        assertThat(repository.lastQuery.eventCreatedFrom()).isEmpty();
        assertThat(repository.lastQuery.eventCreatedTo()).contains(to);
    }

    @Test
    void tenantIsPassedThroughUnchanged() {
        useCase.query(command(50, Optional.empty(), Optional.empty()));

        assertThat(repository.lastTenant).isEqualTo(TENANT);
    }

    @Test
    void cursorIsPropagatedFromRepositoryPage() {
        repository.nextPage = new DeliveryPage(List.of(), Optional.of("cursor-123"));

        QueryNotificationEventsResult result = useCase.query(command(50, Optional.empty(), Optional.empty()));

        assertThat(result.nextCursor()).contains("cursor-123");
    }

    @Test
    void emptyPageYieldsEmptyListAndEmptyCursorNeverNull() {
        repository.nextPage = new DeliveryPage(List.of(), Optional.empty());

        QueryNotificationEventsResult result = useCase.query(command(50, Optional.empty(), Optional.empty()));

        assertThat(result.deliveries()).isEmpty();
        assertThat(result.nextCursor()).isEmpty();
    }

    @Test
    void deliveriesFromThePageArePropagated() {
        Delivery delivery = delivery();
        repository.nextPage = new DeliveryPage(List.of(delivery), Optional.empty());

        QueryNotificationEventsResult result = useCase.query(command(50, Optional.empty(), Optional.empty()));

        assertThat(result.deliveries()).containsExactly(delivery);
    }

    private static QueryNotificationEventsCommand command(
            int limit, Optional<Instant> from, Optional<Instant> to) {
        return new QueryNotificationEventsCommand(
                TENANT, from, to, Optional.empty(), Optional.empty(), limit);
    }

    private static Delivery delivery() {
        return new Delivery(
                UUID.randomUUID(),
                "EVT001",
                UUID.randomUUID(),
                "client-1",
                DeliveryStatus.PENDING,
                DeliveryOrigin.INGEST,
                Optional.empty(),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
    }

    private static class FakeDeliveryQueryRepository implements DeliveryQueryRepositoryPort {
        private TenantId lastTenant;
        private DeliveryPageQuery lastQuery;
        private int lastLimit;
        private DeliveryPage nextPage = new DeliveryPage(List.of(), Optional.empty());

        @Override
        public Optional<Delivery> findById(UUID deliveryId, TenantId tenant) {
            return Optional.empty();
        }

        @Override
        public DeliveryPage findPage(TenantId tenant, DeliveryPageQuery query, int limit) {
            this.lastTenant = tenant;
            this.lastQuery = query;
            this.lastLimit = limit;
            return nextPage;
        }
    }
}
