package com.cobre.challenge.domain.model.delivery.enums;

import com.cobre.challenge.domain.model.delivery.exception.IllegalDeliveryTransitionException;
import java.util.EnumSet;
import java.util.Set;

/**
 * The seven internal delivery states (ADR-003 SS1). Terminal states are
 * {@link #DELIVERED}, {@link #DEAD} and {@link #FAILED}.
 */
public enum DeliveryStatus {
    PENDING {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.of(QUEUED);
        }
    },
    QUEUED {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.of(PROCESSING, QUEUED, FAILED);
        }
    },
    PROCESSING {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.of(DELIVERED, RETRYING, DEAD, QUEUED, FAILED);
        }
    },
    RETRYING {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.of(QUEUED);
        }
    },
    DELIVERED {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.noneOf(DeliveryStatus.class);
        }
    },
    DEAD {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.noneOf(DeliveryStatus.class);
        }
    },
    FAILED {
        @Override
        Set<DeliveryStatus> legalTargets() {
            return EnumSet.noneOf(DeliveryStatus.class);
        }
    };

    abstract Set<DeliveryStatus> legalTargets();

    public boolean isTerminal() {
        return this == DELIVERED || this == DEAD || this == FAILED;
    }

    public Set<DeliveryStatus> allowedTransitions() {
        return Set.copyOf(legalTargets());
    }

    public boolean canTransitionTo(DeliveryStatus target) {
        return legalTargets().contains(target);
    }

    public DeliveryStatus transitionTo(DeliveryStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalDeliveryTransitionException(this, target);
        }
        return target;
    }
}
