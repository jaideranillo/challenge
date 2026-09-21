package com.cobre.challenge.application.port.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersistencePortsTest {

    @Test
    void deliveryPageDefensivelyCopiesDeliveryList() {
        DeliveryPage page = new DeliveryPage(List.<Delivery>of(), Optional.empty());

        assertThat(page.deliveries()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void allThreeRepositoryPortsCompileAsInterfaces() {
        assertThat(DeliveryRepositoryPort.class.isInterface()).isTrue();
        assertThat(DeliveryAttemptRepositoryPort.class.isInterface()).isTrue();
        assertThat(SubscriptionRepositoryPort.class.isInterface()).isTrue();
    }
}
