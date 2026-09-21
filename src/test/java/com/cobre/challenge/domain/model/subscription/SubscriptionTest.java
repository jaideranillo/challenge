package com.cobre.challenge.domain.model.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionTest {

    private Subscription subscription(boolean active, VerificationState state) {
        return new Subscription(
                UUID.randomUUID(), "client-1", "https://example.com/hook", "secret-ref",
                Optional.empty(), Optional.empty(), Set.of("payment.created"),
                active, state, 10, CircuitState.CLOSED, Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({
            "true, VERIFIED, true",
            "true, PENDING_VERIFICATION, false",
            "false, VERIFIED, false",
            "false, PENDING_VERIFICATION, false"
    })
    void isDeliverableTruthTable(boolean active, VerificationState state, boolean expected) {
        assertThat(subscription(active, state).isDeliverable()).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void compactConstructorRejectsNullTargetUrl() {
        assertThatThrownBy(() -> new Subscription(
                UUID.randomUUID(), "client-1", null, "secret-ref",
                Optional.empty(), Optional.empty(), Set.of(),
                true, VerificationState.VERIFIED, 10, CircuitState.CLOSED, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @org.junit.jupiter.api.Test
    void eventTypesAreDefensivelyCopiedAndImmutable() {
        Set<String> mutable = new HashSet<>(Set.of("a", "b"));
        Subscription subscription = new Subscription(
                UUID.randomUUID(), "client-1", "https://example.com", "secret-ref",
                Optional.empty(), Optional.empty(), mutable,
                true, VerificationState.VERIFIED, 10, CircuitState.CLOSED, Optional.empty());

        mutable.add("c");

        assertThat(subscription.eventTypes()).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(() -> subscription.eventTypes().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
