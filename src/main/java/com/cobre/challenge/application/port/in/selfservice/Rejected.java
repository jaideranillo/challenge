package com.cobre.challenge.application.port.in.selfservice;

import com.cobre.challenge.application.port.in.selfservice.dto.RejectionReason;
import java.util.Objects;

public record Rejected(RejectionReason reason) implements ReplayDeliveryResult {

    public Rejected {
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
