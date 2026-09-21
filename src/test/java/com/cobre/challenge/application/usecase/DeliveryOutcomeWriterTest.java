package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.usecase.dto.AttemptOutcomeCommand;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.RetryPolicy;
import com.cobre.challenge.domain.policy.TransportFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/** ADR-004 §1's classification table, proven scenario by scenario against mocked ports. */
class DeliveryOutcomeWriterTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final Instant ATTEMPTED_AT = Instant.parse("2026-09-21T10:00:00Z");
    private static final Duration BASE_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration MAX_COOLDOWN = Duration.ofHours(1);

    private final DeliveryAttemptRepositoryPort attemptPort = mock(DeliveryAttemptRepositoryPort.class);
    private final DeliveryPipelineRepositoryPort pipelinePort = mock(DeliveryPipelineRepositoryPort.class);
    private final SubscriptionRepositoryPort subscriptionPort = mock(SubscriptionRepositoryPort.class);
    private final RetryPolicy retryPolicy = RetryPolicy.defaultSchedule(new Random(42L));
    private final WorkerProperties workerProperties = new WorkerProperties(
            true,
            Duration.ofSeconds(20),
            10,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            new WorkerProperties.Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
            new WorkerProperties.CircuitBreaker(5, BASE_COOLDOWN, MAX_COOLDOWN),
            Duration.ofHours(1),
            1024);

    private final DeliveryOutcomeWriter writer =
            new DeliveryOutcomeWriter(attemptPort, pipelinePort, subscriptionPort, retryPolicy, workerProperties);

    @Test
    void scenario1_successMarksDeliveredInsertsOneAttemptRowAndTouchesNoSubscription() {
        writer.write(aCommand().outcome(AttemptOutcome.SUCCESS).httpStatus(200).build());

        verify(pipelinePort, times(1)).markDelivered(DELIVERY_ID, ATTEMPTED_AT);
        verify(attemptPort, times(1)).insert(any());
        verifyNoInteractions(subscriptionPort);
    }

    @Test
    void scenario2_serverErrorSchedulesRetryWithFutureInstantWithinJitterBand() {
        ArgumentCaptor<Instant> nextAttemptAt = ArgumentCaptor.forClass(Instant.class);

        writer.write(aCommand().outcome(AttemptOutcome.RETRYABLE).httpStatus(500).currentAttemptCount(0).build());

        verify(pipelinePort, times(1))
                .scheduleRetry(eq(DELIVERY_ID), nextAttemptAt.capture(), any(), eq(ATTEMPTED_AT));
        Instant scheduled = nextAttemptAt.getValue();
        Duration nominal = Duration.ofSeconds(5); // schedule[0]
        assertThat(scheduled).isAfter(ATTEMPTED_AT);
        assertThat(scheduled).isAfterOrEqualTo(ATTEMPTED_AT.plusMillis(Math.round(nominal.toMillis() * 0.8)));
        assertThat(scheduled).isBeforeOrEqualTo(ATTEMPTED_AT.plusMillis(Math.round(nominal.toMillis() * 1.2)));
        verify(pipelinePort, never()).markDelivered(any(), any());
        verify(pipelinePort, never()).markDead(any(), any(), any());
    }

    @Test
    void scenario3_clientErrorMarksDeadWithNoRetryAndNoSubscriptionTouch() {
        writer.write(aCommand().outcome(AttemptOutcome.NON_RETRYABLE).httpStatus(400).build());

        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verify(pipelinePort, never()).scheduleRetry(any(), any(), any(), any());
        verifyNoInteractions(subscriptionPort);
    }

    @Test
    void scenario4a_throttledWithRetryAfterSetsThrottledUntilToAttemptedAtPlusRetryAfter() {
        Duration retryAfter = Duration.ofMinutes(2);

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE_THROTTLED)
                .httpStatus(429)
                .retryAfter(retryAfter)
                .build());

        verify(subscriptionPort, times(1))
                .setThrottledUntil(SUBSCRIPTION_ID, ATTEMPTED_AT.plus(retryAfter));
        verify(pipelinePort, times(1)).scheduleRetry(eq(DELIVERY_ID), any(), any(), eq(ATTEMPTED_AT));
    }

    @Test
    void scenario4b_throttledWithoutRetryAfterFallsBackToScheduledRetryInstant() {
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> throttledUntil = ArgumentCaptor.forClass(Instant.class);

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE_THROTTLED)
                .httpStatus(429)
                .build());

        verify(pipelinePort, times(1))
                .scheduleRetry(eq(DELIVERY_ID), scheduledAt.capture(), any(), eq(ATTEMPTED_AT));
        verify(subscriptionPort, times(1)).setThrottledUntil(eq(SUBSCRIPTION_ID), throttledUntil.capture());
        assertThat(throttledUntil.getValue()).isEqualTo(scheduledAt.getValue());
    }

    @Test
    void scenario4c_throttledWithRetryAfterAlreadyClampedToCeilingPassesThroughUntouched() {
        Duration clampedCeiling = Duration.ofHours(1);

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE_THROTTLED)
                .httpStatus(429)
                .retryAfter(clampedCeiling)
                .build());

        verify(subscriptionPort, times(1))
                .setThrottledUntil(SUBSCRIPTION_ID, ATTEMPTED_AT.plus(clampedCeiling));
    }

    @Test
    void scenario4_throttledNeverCallsCircuitMethodsEvenOnAProbe() {
        assertThat(AttemptOutcome.RETRYABLE_THROTTLED.countsTowardCircuitBreaker()).isFalse();

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE_THROTTLED)
                .httpStatus(429)
                .wasHalfOpenProbe(true)
                .build());

        verify(subscriptionPort, never()).reopenCircuit(any(), any(), any(), any());
        verify(subscriptionPort, never()).tripCircuit(any(), any(), any(), any());
    }

    @Test
    void scenario5_deactivateOutcomeMarksDeadAndDeactivatesSubscriptionWithNoCircuitCall() {
        writer.write(aCommand()
                .outcome(AttemptOutcome.NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION)
                .httpStatus(410)
                .build());

        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verify(subscriptionPort, times(1)).deactivate(SUBSCRIPTION_ID);
        verify(subscriptionPort, never()).reopenCircuit(any(), any(), any(), any());
        verify(subscriptionPort, never()).closeCircuit(any(), any());
    }

    @Test
    void scenario6a_redirectOffAProbeMarksDeadAndWritesNothingToSubscriptions() {
        writer.write(aCommand().outcome(AttemptOutcome.NON_RETRYABLE_REDIRECT).httpStatus(301).build());

        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verifyNoInteractions(subscriptionPort);
    }

    @Test
    void scenario6b_redirectOnAProbeMarksDeadAndReopensTheCircuit() {
        writer.write(aCommand()
                .outcome(AttemptOutcome.NON_RETRYABLE_REDIRECT)
                .httpStatus(301)
                .wasHalfOpenProbe(true)
                .build());

        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verify(subscriptionPort, times(1)).reopenCircuit(SUBSCRIPTION_ID, BASE_COOLDOWN, MAX_COOLDOWN, ATTEMPTED_AT);
    }

    @Test
    void scenario7_exhaustedScheduleMarksDeadInsteadOfSchedulingAnotherRetry() {
        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE)
                .httpStatus(500)
                .currentAttemptCount(retryPolicy.maxAttempts())
                .build());

        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verify(pipelinePort, never()).scheduleRetry(any(), any(), any(), any());
    }

    @Test
    void scenario8_successfulProbeClosesTheCircuitInAdditionToMarkingDelivered() {
        writer.write(aCommand().outcome(AttemptOutcome.SUCCESS).httpStatus(200).wasHalfOpenProbe(true).build());

        verify(pipelinePort, times(1)).markDelivered(DELIVERY_ID, ATTEMPTED_AT);
        verify(subscriptionPort, times(1)).closeCircuit(SUBSCRIPTION_ID, ATTEMPTED_AT);
    }

    @Test
    void scenario9_failedProbeReopensTheCircuitWithBaseAndMaxCooldownFromProperties() {
        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE)
                .httpStatus(500)
                .currentAttemptCount(0)
                .wasHalfOpenProbe(true)
                .build());

        verify(subscriptionPort, times(1)).reopenCircuit(SUBSCRIPTION_ID, BASE_COOLDOWN, MAX_COOLDOWN, ATTEMPTED_AT);
    }

    @ParameterizedTest
    @EnumSource(AttemptOutcome.class)
    void scenario10_everyOutcomeInsertsExactlyOneAttemptRow(AttemptOutcome outcome) {
        writer.write(aCommand().outcome(outcome).httpStatus(httpStatusFor(outcome)).currentAttemptCount(0).build());

        verify(attemptPort, times(1)).insert(any());
    }

    @Test
    void scenario11_aFalseConditionalWriteIsToleratedAndDoesNotThrow() {
        when(pipelinePort.markDelivered(any(), any())).thenReturn(false);

        writer.write(aCommand().outcome(AttemptOutcome.SUCCESS).httpStatus(200).build());

        verify(pipelinePort, times(1)).markDelivered(DELIVERY_ID, ATTEMPTED_AT);
    }

    @Test
    void scenario12a_egressPolicyRejectionRecordsAbsentHttpStatusAndMarksDead() {
        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);

        writer.write(aCommand()
                .outcome(AttemptOutcome.NON_RETRYABLE)
                .httpStatusAbsent()
                .transportFailure(TransportFailure.NONE)
                .build());

        verify(attemptPort, times(1)).insert(attempt.capture());
        assertThat(attempt.getValue().httpStatus()).isEmpty();
        assertThat(attempt.getValue().error()).contains("NON_RETRYABLE");
        verify(pipelinePort, times(1)).markDead(eq(DELIVERY_ID), any(), eq(ATTEMPTED_AT));
        verifyNoInteractions(subscriptionPort);
        assertThat(AttemptOutcome.values()).hasSize(6);
    }

    @Test
    void scenario12b_dnsFailureRecordsAbsentHttpStatusAndSchedulesRetry() {
        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE)
                .httpStatusAbsent()
                .transportFailure(TransportFailure.DNS_FAILURE)
                .currentAttemptCount(0)
                .build());

        verify(attemptPort, times(1)).insert(attempt.capture());
        assertThat(attempt.getValue().httpStatus()).isEmpty();
        assertThat(attempt.getValue().error()).contains("DNS_FAILURE");
        verify(pipelinePort, times(1)).scheduleRetry(eq(DELIVERY_ID), any(), any(), eq(ATTEMPTED_AT));
        assertThat(AttemptOutcome.values()).hasSize(6);
    }

    @Test
    void scenario13_lastErrorCarriesNoResponsePayload() {
        ArgumentCaptor<String> lastError = ArgumentCaptor.forClass(String.class);
        String sensitiveExcerpt = "SENSITIVE_RESPONSE_BODY_EXCERPT";

        writer.write(aCommand()
                .outcome(AttemptOutcome.RETRYABLE)
                .httpStatus(500)
                .responseExcerpt(sensitiveExcerpt)
                .currentAttemptCount(0)
                .build());

        verify(pipelinePort, times(1))
                .scheduleRetry(eq(DELIVERY_ID), any(), lastError.capture(), eq(ATTEMPTED_AT));
        assertThat(lastError.getValue()).doesNotContain(sensitiveExcerpt);
        assertThat(lastError.getValue()).isEqualTo("HTTP 500");
    }

    private static OptionalInt httpStatusFor(AttemptOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> OptionalInt.of(200);
            case RETRYABLE -> OptionalInt.of(500);
            case RETRYABLE_THROTTLED -> OptionalInt.of(429);
            case NON_RETRYABLE -> OptionalInt.of(400);
            case NON_RETRYABLE_REDIRECT -> OptionalInt.of(301);
            case NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION -> OptionalInt.of(404);
        };
    }

    private static CommandBuilder aCommand() {
        return new CommandBuilder();
    }

    /** Test-only builder: {@link AttemptOutcomeCommand} has too many axes to construct inline per scenario. */
    private static final class CommandBuilder {
        private AttemptOutcome outcome = AttemptOutcome.SUCCESS;
        private OptionalInt httpStatus = OptionalInt.of(200);
        private TransportFailure transportFailure = TransportFailure.NONE;
        private Optional<String> error = Optional.empty();
        private Optional<String> responseExcerpt = Optional.empty();
        private Optional<Duration> retryAfter = Optional.empty();
        private boolean wasHalfOpenProbe = false;
        private int currentAttemptCount = 0;

        CommandBuilder outcome(AttemptOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        CommandBuilder httpStatus(int status) {
            this.httpStatus = OptionalInt.of(status);
            return this;
        }

        CommandBuilder httpStatus(OptionalInt status) {
            this.httpStatus = status;
            return this;
        }

        CommandBuilder httpStatusAbsent() {
            this.httpStatus = OptionalInt.empty();
            return this;
        }

        CommandBuilder transportFailure(TransportFailure transportFailure) {
            this.transportFailure = transportFailure;
            return this;
        }

        CommandBuilder error(String error) {
            this.error = Optional.of(error);
            return this;
        }

        CommandBuilder responseExcerpt(String excerpt) {
            this.responseExcerpt = Optional.of(excerpt);
            return this;
        }

        CommandBuilder retryAfter(Duration retryAfter) {
            this.retryAfter = Optional.of(retryAfter);
            return this;
        }

        CommandBuilder wasHalfOpenProbe(boolean wasHalfOpenProbe) {
            this.wasHalfOpenProbe = wasHalfOpenProbe;
            return this;
        }

        CommandBuilder currentAttemptCount(int currentAttemptCount) {
            this.currentAttemptCount = currentAttemptCount;
            return this;
        }

        AttemptOutcomeCommand build() {
            return new AttemptOutcomeCommand(
                    DELIVERY_ID,
                    SUBSCRIPTION_ID,
                    currentAttemptCount + 1,
                    ATTEMPTED_AT,
                    outcome,
                    httpStatus,
                    transportFailure,
                    error,
                    100,
                    responseExcerpt,
                    retryAfter,
                    wasHalfOpenProbe,
                    currentAttemptCount);
        }
    }
}
