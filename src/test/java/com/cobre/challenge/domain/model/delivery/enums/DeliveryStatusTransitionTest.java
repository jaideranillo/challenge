package com.cobre.challenge.domain.model.delivery.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.domain.model.delivery.exception.IllegalDeliveryTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DeliveryStatusTransitionTest {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> LEGAL = new EnumMap<>(DeliveryStatus.class);

    static {
        LEGAL.put(DeliveryStatus.PENDING, EnumSet.of(DeliveryStatus.QUEUED));
        LEGAL.put(DeliveryStatus.QUEUED,
                EnumSet.of(DeliveryStatus.PROCESSING, DeliveryStatus.QUEUED, DeliveryStatus.FAILED));
        LEGAL.put(DeliveryStatus.PROCESSING, EnumSet.of(
                DeliveryStatus.DELIVERED, DeliveryStatus.RETRYING, DeliveryStatus.DEAD,
                DeliveryStatus.QUEUED, DeliveryStatus.FAILED));
        LEGAL.put(DeliveryStatus.RETRYING, EnumSet.of(DeliveryStatus.QUEUED));
        LEGAL.put(DeliveryStatus.DELIVERED, EnumSet.noneOf(DeliveryStatus.class));
        LEGAL.put(DeliveryStatus.DEAD, EnumSet.noneOf(DeliveryStatus.class));
        LEGAL.put(DeliveryStatus.FAILED, EnumSet.noneOf(DeliveryStatus.class));
    }

    private static Stream<Arguments> legalPairs() {
        return LEGAL.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(to -> Arguments.of(e.getKey(), to)));
    }

    private static Stream<Arguments> illegalPairs() {
        return Stream.of(DeliveryStatus.values())
                .flatMap(from -> Stream.of(DeliveryStatus.values())
                        .filter(to -> !LEGAL.get(from).contains(to))
                        .map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("legalPairs")
    void legalTransitionSucceeds(DeliveryStatus from, DeliveryStatus to) {
        assertThat(from.transitionTo(to)).isEqualTo(to);
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("illegalPairs")
    void illegalTransitionThrows(DeliveryStatus from, DeliveryStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.transitionTo(to))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void allPairsAreExhaustivelyCovered() {
        long legalCount = legalPairs().count();
        long illegalCount = illegalPairs().count();
        assertThat(legalCount + illegalCount)
                .isEqualTo((long) DeliveryStatus.values().length * DeliveryStatus.values().length);
    }

    @Test
    void deadToPendingThrows() {
        assertThatThrownBy(() -> DeliveryStatus.DEAD.transitionTo(DeliveryStatus.PENDING))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void failedToPendingThrows() {
        assertThatThrownBy(() -> DeliveryStatus.FAILED.transitionTo(DeliveryStatus.PENDING))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void queuedToPendingThrows() {
        assertThatThrownBy(() -> DeliveryStatus.QUEUED.transitionTo(DeliveryStatus.PENDING))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void processingToPendingThrows() {
        assertThatThrownBy(() -> DeliveryStatus.PROCESSING.transitionTo(DeliveryStatus.PENDING))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
    }

    @Test
    void queuedToQueuedSucceeds() {
        assertThat(DeliveryStatus.QUEUED.transitionTo(DeliveryStatus.QUEUED)).isEqualTo(DeliveryStatus.QUEUED);
    }

    @Test
    void terminalStatesHaveEmptyAllowedTransitions() {
        assertThat(DeliveryStatus.DELIVERED.allowedTransitions()).isEmpty();
        assertThat(DeliveryStatus.DEAD.allowedTransitions()).isEmpty();
        assertThat(DeliveryStatus.FAILED.allowedTransitions()).isEmpty();
    }

    @Test
    void exceptionCarriesAttemptedPair() {
        IllegalDeliveryTransitionException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalDeliveryTransitionException.class,
                () -> DeliveryStatus.DEAD.transitionTo(DeliveryStatus.PENDING));

        assertThat(exception.from()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(exception.to()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(exception.getMessage()).contains("DEAD").contains("PENDING");
    }
}
