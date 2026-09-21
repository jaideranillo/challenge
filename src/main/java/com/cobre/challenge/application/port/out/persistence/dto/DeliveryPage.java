package com.cobre.challenge.application.port.out.persistence.dto;

import com.cobre.challenge.domain.model.delivery.Delivery;
import java.util.List;
import java.util.Optional;

public record DeliveryPage(List<Delivery> deliveries, Optional<String> nextCursor) {

    public DeliveryPage {
        deliveries = List.copyOf(deliveries);
    }
}
