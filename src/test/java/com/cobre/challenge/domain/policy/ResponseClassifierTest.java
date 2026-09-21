package com.cobre.challenge.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204, 299})
    void twoXxIsSuccess(int status) {
        assertThat(ResponseClassifier.classify(status, TransportFailure.NONE)).isEqualTo(AttemptOutcome.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void threeXxIsNonRetryableRedirectAndCountsTowardBreaker(int status) {
        AttemptOutcome outcome = ResponseClassifier.classify(status, TransportFailure.NONE);
        assertThat(outcome).isEqualTo(AttemptOutcome.NON_RETRYABLE_REDIRECT);
        assertThat(outcome.countsTowardCircuitBreaker()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 422})
    void badRequestAndUnprocessableAreNonRetryable(int status) {
        assertThat(ResponseClassifier.classify(status, TransportFailure.NONE))
                .isEqualTo(AttemptOutcome.NON_RETRYABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void unauthorizedAndForbiddenAreNonRetryable(int status) {
        assertThat(ResponseClassifier.classify(status, TransportFailure.NONE))
                .isEqualTo(AttemptOutcome.NON_RETRYABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void notFoundAndGoneDeactivateSubscription(int status) {
        assertThat(ResponseClassifier.classify(status, TransportFailure.NONE))
                .isEqualTo(AttemptOutcome.NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION);
    }

    @Test
    void requestTimeoutIsRetryableAndCountsTowardBreaker() {
        AttemptOutcome outcome = ResponseClassifier.classify(408, TransportFailure.NONE);
        assertThat(outcome).isEqualTo(AttemptOutcome.RETRYABLE);
        assertThat(outcome.countsTowardCircuitBreaker()).isTrue();
    }

    @Test
    void tooManyRequestsIsRetryableThrottledAndDoesNotCountTowardBreaker() {
        AttemptOutcome outcome = ResponseClassifier.classify(429, TransportFailure.NONE);
        assertThat(outcome).isEqualTo(AttemptOutcome.RETRYABLE_THROTTLED);
        assertThat(outcome.countsTowardCircuitBreaker()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 599})
    void fiveXxIsRetryableAndCountsTowardBreaker(int status) {
        AttemptOutcome outcome = ResponseClassifier.classify(status, TransportFailure.NONE);
        assertThat(outcome).isEqualTo(AttemptOutcome.RETRYABLE);
        assertThat(outcome.countsTowardCircuitBreaker()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TransportFailure.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void transportFailuresAreRetryableAndCountTowardBreaker(TransportFailure failure) {
        AttemptOutcome outcome = ResponseClassifier.classify(0, failure);
        assertThat(outcome).isEqualTo(AttemptOutcome.RETRYABLE);
        assertThat(outcome.countsTowardCircuitBreaker()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {418, 451})
    void unnamedFourXxDefaultsToNonRetryable(int status) {
        assertThat(ResponseClassifier.classify(status, TransportFailure.NONE))
                .isEqualTo(AttemptOutcome.NON_RETRYABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600})
    void outOfRangeStatusWithoutTransportFailureThrows(int status) {
        assertThatThrownBy(() -> ResponseClassifier.classify(status, TransportFailure.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyAttemptOutcomeValueIsProducedBySomeInputInThisSuite() {
        Set<AttemptOutcome> produced = EnumSet.noneOf(AttemptOutcome.class);
        produced.add(ResponseClassifier.classify(200, TransportFailure.NONE));
        produced.add(ResponseClassifier.classify(301, TransportFailure.NONE));
        produced.add(ResponseClassifier.classify(400, TransportFailure.NONE));
        produced.add(ResponseClassifier.classify(404, TransportFailure.NONE));
        produced.add(ResponseClassifier.classify(429, TransportFailure.NONE));
        produced.add(ResponseClassifier.classify(500, TransportFailure.NONE));

        assertThat(produced).containsExactlyInAnyOrderElementsOf(Stream.of(AttemptOutcome.values()).toList());
    }
}
