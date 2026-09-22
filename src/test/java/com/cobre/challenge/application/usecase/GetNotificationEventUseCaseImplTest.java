package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.in.selfservice.dto.GetNotificationEventCommand;
import com.cobre.challenge.application.port.in.selfservice.dto.NotificationEventDetail;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.NotificationEventQueryRepositoryPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetNotificationEventUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final TenantId TENANT = new TenantId("client-1");
    private static final TenantId OTHER_TENANT = new TenantId("client-2");

    private final FakeDeliveryQueryRepository deliveryQueryRepository = new FakeDeliveryQueryRepository();
    private final FakeNotificationEventQueryRepository eventQueryRepository =
            new FakeNotificationEventQueryRepository();
    private final FakeDeliveryAttemptQueryRepository attemptQueryRepository =
            new FakeDeliveryAttemptQueryRepository();

    private final GetNotificationEventUseCaseImpl useCase = new GetNotificationEventUseCaseImpl(
            deliveryQueryRepository, eventQueryRepository, attemptQueryRepository);

    @Test
    void ownedIdReturnsTheAssembledDetailWithAllAttempts() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = delivery(deliveryId, "EVT001");
        NotificationEvent event = event("EVT001");
        DeliveryAttempt attempt1 = attempt(deliveryId, 1);
        DeliveryAttempt attempt2 = attempt(deliveryId, 2);

        deliveryQueryRepository.put(deliveryId, TENANT, delivery);
        eventQueryRepository.put("EVT001", TENANT, event);
        attemptQueryRepository.put(deliveryId, TENANT, List.of(attempt1, attempt2));

        Optional<NotificationEventDetail> result = useCase.get(new GetNotificationEventCommand(deliveryId, TENANT));

        assertThat(result).isPresent();
        assertThat(result.get().delivery()).isEqualTo(delivery);
        assertThat(result.get().notificationEvent()).isEqualTo(event);
        assertThat(result.get().attempts()).containsExactly(attempt1, attempt2);
    }

    @Test
    void unknownIdReturnsEmpty() {
        Optional<NotificationEventDetail> result =
                useCase.get(new GetNotificationEventCommand(UUID.randomUUID(), TENANT));

        assertThat(result).isEmpty();
    }

    @Test
    void foreignIdReturnsEmptyAndTheOtherTwoPortsAreNeverCalled() {
        UUID deliveryId = UUID.randomUUID();
        deliveryQueryRepository.put(deliveryId, OTHER_TENANT, delivery(deliveryId, "EVT001"));

        Optional<NotificationEventDetail> result =
                useCase.get(new GetNotificationEventCommand(deliveryId, TENANT));

        assertThat(result).isEmpty();
        assertThat(eventQueryRepository.callCount).isZero();
        assertThat(attemptQueryRepository.callCount).isZero();
    }

    @Test
    void deliveryWithZeroAttemptsReturnsAnEmptyListNeverNull() {
        UUID deliveryId = UUID.randomUUID();
        deliveryQueryRepository.put(deliveryId, TENANT, delivery(deliveryId, "EVT001"));
        eventQueryRepository.put("EVT001", TENANT, event("EVT001"));

        Optional<NotificationEventDetail> result = useCase.get(new GetNotificationEventCommand(deliveryId, TENANT));

        assertThat(result).isPresent();
        assertThat(result.get().attempts()).isEmpty();
    }

    @Test
    void missingEventThrows() {
        UUID deliveryId = UUID.randomUUID();
        deliveryQueryRepository.put(deliveryId, TENANT, delivery(deliveryId, "EVT001"));
        // no event registered for EVT001 - data-integrity violation

        assertThatThrownBy(() -> useCase.get(new GetNotificationEventCommand(deliveryId, TENANT)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Delivery delivery(UUID deliveryId, String eventId) {
        return new Delivery(
                deliveryId,
                eventId,
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
                Optional.empty());
    }

    private static NotificationEvent event(String eventId) {
        return new NotificationEvent(eventId, "client-1", "payment.created", "content", NOW);
    }

    private static DeliveryAttempt attempt(UUID deliveryId, int attemptNumber) {
        return new DeliveryAttempt(
                deliveryId, attemptNumber, OptionalInt.of(200), 120, Optional.empty(), Optional.empty(), NOW);
    }

    private static class FakeDeliveryQueryRepository implements DeliveryQueryRepositoryPort {
        private final Map<TenantId, Map<UUID, Delivery>> byTenant = new HashMap<>();

        void put(UUID deliveryId, TenantId tenant, Delivery delivery) {
            byTenant.computeIfAbsent(tenant, t -> new HashMap<>()).put(deliveryId, delivery);
        }

        @Override
        public Optional<Delivery> findById(UUID deliveryId, TenantId tenant) {
            return Optional.ofNullable(byTenant.getOrDefault(tenant, Map.of()).get(deliveryId));
        }

        @Override
        public com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage findPage(
                TenantId tenant,
                com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery query,
                int limit) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static class FakeNotificationEventQueryRepository implements NotificationEventQueryRepositoryPort {
        private final Map<TenantId, Map<String, NotificationEvent>> byTenant = new HashMap<>();
        private int callCount;

        void put(String eventId, TenantId tenant, NotificationEvent event) {
            byTenant.computeIfAbsent(tenant, t -> new HashMap<>()).put(eventId, event);
        }

        @Override
        public Optional<NotificationEvent> findById(String eventId, TenantId tenant) {
            callCount++;
            return Optional.ofNullable(byTenant.getOrDefault(tenant, Map.of()).get(eventId));
        }
    }

    private static class FakeDeliveryAttemptQueryRepository implements DeliveryAttemptQueryRepositoryPort {
        private final Map<TenantId, Map<UUID, List<DeliveryAttempt>>> byTenant = new HashMap<>();
        private int callCount;

        void put(UUID deliveryId, TenantId tenant, List<DeliveryAttempt> attempts) {
            byTenant.computeIfAbsent(tenant, t -> new HashMap<>()).put(deliveryId, attempts);
        }

        @Override
        public List<DeliveryAttempt> findByDeliveryId(UUID deliveryId, TenantId tenant) {
            callCount++;
            return byTenant.getOrDefault(tenant, Map.of()).getOrDefault(deliveryId, List.of());
        }
    }
}
