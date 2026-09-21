package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.out.webhook.OutboundUrlValidator;
import com.cobre.challenge.adapter.out.webhook.dto.EgressVerdict;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.NotificationEventRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.port.out.resilience.BulkheadPort;
import com.cobre.challenge.application.port.out.resilience.CircuitBreakerPort;
import com.cobre.challenge.application.port.out.secrets.WebhookSecretPort;
import com.cobre.challenge.application.port.out.webhook.WebhookClientPort;
import com.cobre.challenge.application.port.out.webhook.WebhookEnvelopeSerializerPort;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookEnvelope;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;
import com.cobre.challenge.application.usecase.dto.AttemptOutcomeCommand;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.subscription.Subscription;
import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.TransportFailure;
import com.cobre.challenge.domain.policy.WebhookSigner;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * TASK-007-15/TASK-007-20: the fixed per-message order and its branches (ADR-002 SS2.2), plus
 * the egress pre-flight seam in step 5. Plain JUnit/Mockito, no Spring context (FEAT-007 testing
 * phase rule). {@link DeliveryOutcomeWriter} is mocked throughout: this class only asserts what
 * the use case hands it, not the writer's own transactional behavior (that is
 * {@link DeliveryOutcomeWriterTest}'s job) — in particular, the {@code reopenCircuit}/
 * {@code closeCircuit} calls a probe eventually causes are proven there (scenarios 6b/8/9), not
 * here; this class proves only that the command carries the right {@code wasHalfOpenProbe} and
 * {@code outcome} for the writer to act on.
 */
class AttemptDeliveryUseCaseImplTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final String EVENT_ID = "evt-1";
    private static final String SECRET_REF = "secret-ref";
    private static final String PREVIOUS_SECRET_REF = "previous-secret-ref";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private DeliveryPipelineRepositoryPort pipelinePort;
    private SubscriptionRepositoryPort subscriptionPort;
    private NotificationEventRepositoryPort eventPort;
    private BulkheadPort bulkheadPort;
    private CircuitBreakerPort circuitBreakerPort;
    private WebhookSecretPort secretPort;
    private WebhookEnvelopeSerializerPort serializerPort;
    private WebhookClientPort webhookClientPort;
    private OutboundUrlValidator urlValidator;
    private DeliveryOutcomeWriter outcomeWriter;
    private SimpleMeterRegistry meterRegistry;
    private AttemptDeliveryUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        pipelinePort = mock(DeliveryPipelineRepositoryPort.class);
        subscriptionPort = mock(SubscriptionRepositoryPort.class);
        eventPort = mock(NotificationEventRepositoryPort.class);
        bulkheadPort = mock(BulkheadPort.class);
        circuitBreakerPort = mock(CircuitBreakerPort.class);
        secretPort = mock(WebhookSecretPort.class);
        serializerPort = mock(WebhookEnvelopeSerializerPort.class);
        webhookClientPort = mock(WebhookClientPort.class);
        urlValidator = mock(OutboundUrlValidator.class);
        outcomeWriter = mock(DeliveryOutcomeWriter.class);
        meterRegistry = new SimpleMeterRegistry();

        when(pipelinePort.claimForProcessing(any(), any())).thenReturn(true);
        when(pipelinePort.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery()));
        when(subscriptionPort.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(eventPort.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(bulkheadPort.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        when(secretPort.resolve(SECRET_REF)).thenReturn(Optional.of("secret-material"));
        when(serializerPort.serialize(any())).thenReturn("{}");

        useCase = new AttemptDeliveryUseCaseImpl(
                pipelinePort, subscriptionPort, eventPort, bulkheadPort, circuitBreakerPort, secretPort,
                serializerPort, webhookClientPort, urlValidator, outcomeWriter, workerProperties(),
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), RandomGenerator.getDefault());
    }

    @Test
    void policyRejected_skipsThePost_doesNotTouchTheBreaker_andRaisesTheSecuritySignal() {
        when(urlValidator.validate(anyString()))
                .thenReturn(EgressVerdict.policyRejected("egress: target resolves to a blocked range"));

        AttemptDeliveryResult result = useCase.attempt(command());

        verifyNoInteractions(webhookClientPort);
        verify(circuitBreakerPort, never()).recordFailure(any());
        verify(subscriptionPort, never()).tripCircuit(any(), any(), any(), any());

        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        AttemptOutcomeCommand written = captor.getValue();
        assertThat(written.outcome()).isEqualTo(AttemptOutcome.NON_RETRYABLE);
        assertThat(written.httpStatus()).isEmpty();
        assertThat(written.error()).contains("egress: target resolves to a blocked range");

        assertThat(meterRegistry.counter("notification.webhook.egress.rejected").count()).isEqualTo(1.0);
        assertThat(result.outcome()).contains(AttemptOutcome.NON_RETRYABLE);
    }

    @Test
    void policyRejected_withResolvedAddress_logsItAlongsideTheHost() {
        when(urlValidator.validate(anyString()))
                .thenReturn(EgressVerdict.policyRejected("egress: target resolves to a blocked range", "169.254.169.254"));

        Logger useCaseLogger = (Logger) LoggerFactory.getLogger(AttemptDeliveryUseCaseImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        useCaseLogger.addAppender(appender);

        try {
            useCase.attempt(command());

            ILoggingEvent warnEvent = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("Egress policy rejected"))
                    .findFirst()
                    .orElseThrow();
            assertThat(warnEvent.getFormattedMessage())
                    .contains("delivery_id", "subscription_id", "example.com", "169.254.169.254")
                    .doesNotContain("null");
        } finally {
            useCaseLogger.detachAppender(appender);
        }
    }

    @Test
    void policyRejected_withoutResolvedAddress_omitsItCleanlyFromTheLog() {
        when(urlValidator.validate(anyString()))
                .thenReturn(EgressVerdict.policyRejected("egress: scheme must be https"));

        Logger useCaseLogger = (Logger) LoggerFactory.getLogger(AttemptDeliveryUseCaseImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        useCaseLogger.addAppender(appender);

        try {
            useCase.attempt(command());

            ILoggingEvent warnEvent = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("Egress policy rejected"))
                    .findFirst()
                    .orElseThrow();
            assertThat(warnEvent.getFormattedMessage()).doesNotContain("resolvedAddress").doesNotContain("null");
        } finally {
            useCaseLogger.detachAppender(appender);
        }
    }

    @Test
    void dnsFailure_skipsThePost_countsTowardTheBreaker_andRaisesNoSecuritySignal() {
        when(urlValidator.validate(anyString()))
                .thenReturn(EgressVerdict.dnsFailure("egress: DNS resolution failed for host"));

        AttemptDeliveryResult result = useCase.attempt(command());

        verifyNoInteractions(webhookClientPort);
        verify(circuitBreakerPort, times(1)).recordFailure(SUBSCRIPTION_ID);

        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        AttemptOutcomeCommand written = captor.getValue();
        assertThat(written.outcome()).isEqualTo(AttemptOutcome.RETRYABLE);
        assertThat(written.httpStatus()).isEmpty();
        assertThat(written.error()).contains("egress: DNS resolution failed for host");

        assertThat(meterRegistry.counter("notification.webhook.egress.rejected").count()).isZero();
        assertThat(result.outcome()).contains(AttemptOutcome.RETRYABLE);
    }

    @Test
    void allowedVerdict_proceedsToThePost_unchanged() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        AttemptDeliveryResult result = useCase.attempt(command());

        verify(webhookClientPort, times(1)).send(any());
        assertThat(result.outcome()).contains(AttemptOutcome.SUCCESS);
        assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(meterRegistry.counter("notification.webhook.egress.rejected").count()).isZero();
    }

    @Test
    void theValidatorIsConsultedOnEveryAttempt_noCachedVerdict() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());
        useCase.attempt(command());

        verify(urlValidator, times(2)).validate(anyString());
    }

    @Test
    void scenario1_zeroRowClaimShortCircuitsBeforeTouchingAnyOtherPort() {
        when(pipelinePort.claimForProcessing(any(), any())).thenReturn(false);

        AttemptDeliveryResult result = useCase.attempt(command());

        assertThat(result.outcome()).isEmpty();
        assertThat(result.status()).isEqualTo(DeliveryStatus.QUEUED);

        verify(pipelinePort, never()).findById(any());
        verifyNoInteractions(webhookClientPort, serializerPort, secretPort, bulkheadPort, outcomeWriter);
    }

    @Test
    void scenario2_bulkheadRejectionDefersWithJitterAndRecordsNothing() {
        when(bulkheadPort.tryAcquire(any(), anyInt(), any())).thenReturn(false);

        AttemptDeliveryResult result = useCase.attempt(command());

        assertThat(result.outcome()).isEmpty();
        assertThat(result.status()).isEqualTo(DeliveryStatus.QUEUED);

        ArgumentCaptor<Instant> deferredUntil = ArgumentCaptor.forClass(Instant.class);
        verify(pipelinePort, times(1)).deferDelivery(eq(DELIVERY_ID), deferredUntil.capture());
        assertThat(deferredUntil.getValue()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(20));

        verifyNoInteractions(webhookClientPort, outcomeWriter);
        verify(pipelinePort, never()).scheduleRetry(any(), any(), any(), any());
        verify(pipelinePort, never()).markDead(any(), any(), any());
        verify(pipelinePort, never()).markDelivered(any(), any());
    }

    @Test
    void scenario2_bulkheadJitterDiffersAcrossSeeds() {
        when(bulkheadPort.tryAcquire(any(), anyInt(), any())).thenReturn(false);

        newUseCase(new Random(1L)).attempt(command());
        newUseCase(new Random(2L)).attempt(command());

        ArgumentCaptor<Instant> deferredUntil = ArgumentCaptor.forClass(Instant.class);
        verify(pipelinePort, times(2)).deferDelivery(eq(DELIVERY_ID), deferredUntil.capture());
        List<Instant> captured = deferredUntil.getAllValues();
        assertThat(captured.get(0)).isNotEqualTo(captured.get(1));
        for (Instant instant : captured) {
            assertThat(instant).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(20));
        }
    }

    @Test
    void scenario3_openCircuitDefersByTheSamePathAsBulkheadRejection() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithCircuitState(CircuitState.OPEN)));

        AttemptDeliveryResult result = useCase.attempt(command());

        assertThat(result.outcome()).isEmpty();
        assertThat(result.status()).isEqualTo(DeliveryStatus.QUEUED);

        ArgumentCaptor<Instant> deferredUntil = ArgumentCaptor.forClass(Instant.class);
        verify(pipelinePort, times(1)).deferDelivery(eq(DELIVERY_ID), deferredUntil.capture());
        assertThat(deferredUntil.getValue()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(20));

        verifyNoInteractions(webhookClientPort, outcomeWriter);
        verify(pipelinePort, never()).scheduleRetry(any(), any(), any(), any());
        verify(pipelinePort, never()).markDead(any(), any(), any());
        verify(pipelinePort, never()).markDelivered(any(), any());
    }

    @Test
    void scenario4_halfOpenCircuitProceedsAsTheProbe() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithCircuitState(CircuitState.HALF_OPEN)));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(webhookClientPort, times(1)).send(any());
        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        assertThat(captor.getValue().wasHalfOpenProbe()).isTrue();
    }

    @Test
    void scenario5_consecutiveFailuresTripTheCircuitWithExactlyOneConditionalWrite() {
        FakeCircuitBreakerPort fakeBreaker = new FakeCircuitBreakerPort(10);
        AttemptDeliveryUseCaseImpl scenarioUseCase = new AttemptDeliveryUseCaseImpl(
                pipelinePort, subscriptionPort, eventPort, bulkheadPort, fakeBreaker, secretPort,
                serializerPort, webhookClientPort, urlValidator, outcomeWriter, workerProperties(),
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), RandomGenerator.getDefault());
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(500, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        for (int i = 0; i < 12; i++) {
            scenarioUseCase.attempt(command());
        }

        verify(subscriptionPort, times(1)).tripCircuit(eq(SUBSCRIPTION_ID), any(), any(), any());
    }

    @Test
    void scenario6_failedHalfOpenProbeCarriesTheReopenSignalToTheWriter() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithCircuitState(CircuitState.HALF_OPEN)));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(500, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(circuitBreakerPort, never()).recordFailure(any());
        verify(subscriptionPort, never()).tripCircuit(any(), any(), any(), any());

        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        AttemptOutcomeCommand written = captor.getValue();
        assertThat(written.wasHalfOpenProbe()).isTrue();
        assertThat(written.outcome()).isEqualTo(AttemptOutcome.RETRYABLE);
        assertThat(written.outcome().countsTowardCircuitBreaker()).isTrue();
    }

    @Test
    void scenario7_successfulProbeClosesTheCircuitThroughTheWritersCommand() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithCircuitState(CircuitState.HALF_OPEN)));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(circuitBreakerPort, times(1)).recordSuccess(SUBSCRIPTION_ID);
        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        assertThat(captor.getValue().wasHalfOpenProbe()).isTrue();
        assertThat(captor.getValue().outcome()).isEqualTo(AttemptOutcome.SUCCESS);
    }

    @Test
    void scenario8_throttledResponseNeverReachesTheBreaker() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(429, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(circuitBreakerPort, never()).recordFailure(any());
        verify(subscriptionPort, never()).tripCircuit(any(), any(), any(), any());

        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(AttemptOutcome.RETRYABLE_THROTTLED);
    }

    @Test
    void scenario9_closedCircuitServerErrorRecordsFailureButDoesNotTripWithoutTheEdge() {
        when(circuitBreakerPort.recordFailure(SUBSCRIPTION_ID)).thenReturn(false);
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(500, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(circuitBreakerPort, times(1)).recordFailure(SUBSCRIPTION_ID);
        verify(subscriptionPort, never()).tripCircuit(any(), any(), any(), any());
        verify(subscriptionPort, never()).reopenCircuit(any(), any(), any(), any());
        verify(subscriptionPort, never()).closeCircuit(any(), any());
        verify(subscriptionPort, never()).deactivate(any());
        verify(subscriptionPort, never()).setThrottledUntil(any(), any());
    }

    @Test
    void scenario10_unresolvableSecretFailsClosedWithoutSendingTheRequest() {
        when(secretPort.resolve(SECRET_REF)).thenReturn(Optional.empty());

        useCase.attempt(command());

        verifyNoInteractions(webhookClientPort);

        ArgumentCaptor<AttemptOutcomeCommand> captor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(captor.capture());
        AttemptOutcomeCommand written = captor.getValue();
        assertThat(written.outcome()).isEqualTo(AttemptOutcome.NON_RETRYABLE);
        assertThat(written.error()).contains("secret unresolved");
    }

    @Test
    void scenario11_rotationWindowStillOpen_includesBothSignatureHeaders() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithPreviousSecret(NOW.plusSeconds(60))));
        when(secretPort.resolve(PREVIOUS_SECRET_REF)).thenReturn(Optional.of("previous-secret-material"));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        ArgumentCaptor<WebhookRequest> captor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClientPort).send(captor.capture());
        assertThat(captor.getValue().headers())
                .containsKeys(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.SIGNATURE_PREVIOUS_HEADER);
    }

    @Test
    void scenario11_rotationWindowClosed_omitsThePreviousSignatureHeader() {
        when(subscriptionPort.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscriptionWithPreviousSecret(NOW.minusSeconds(60))));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        ArgumentCaptor<WebhookRequest> captor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClientPort).send(captor.capture());
        assertThat(captor.getValue().headers()).containsKey(WebhookSigner.SIGNATURE_HEADER);
        assertThat(captor.getValue().headers()).doesNotContainKey(WebhookSigner.SIGNATURE_PREVIOUS_HEADER);
        verify(secretPort, never()).resolve(PREVIOUS_SECRET_REF);
    }

    @Test
    void scenario12_permitReleasedExactlyOnceOnSuccess() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(bulkheadPort, times(1)).release(SUBSCRIPTION_ID);
    }

    @Test
    void scenario12_permitReleasedExactlyOnceOnFailure() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(500, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        verify(bulkheadPort, times(1)).release(SUBSCRIPTION_ID);
    }

    @Test
    void scenario12_permitReleasedExactlyOnceEvenWhenTheClientPortThrows() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> useCase.attempt(command())).isInstanceOf(RuntimeException.class);

        verify(bulkheadPort, times(1)).release(SUBSCRIPTION_ID);
    }

    @Test
    void scenario13_attemptNumberComesFromTheRowNotTheCommandHint() {
        when(pipelinePort.findById(DELIVERY_ID)).thenReturn(Optional.of(deliveryWithAttemptCount(4)));
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(new AttemptDeliveryCommand(DELIVERY_ID, SUBSCRIPTION_ID, 99, Optional.empty()));

        ArgumentCaptor<WebhookEnvelope> captor = ArgumentCaptor.forClass(WebhookEnvelope.class);
        verify(serializerPort).serialize(captor.capture());
        assertThat(captor.getValue().attempt()).isEqualTo(5);
    }

    @Test
    void scenario16_oneInstantIsUsedForTheClaimTheHeaderAndTheWritersCommand() {
        when(urlValidator.validate(anyString())).thenReturn(EgressVerdict.allowed());
        when(webhookClientPort.send(any()))
                .thenReturn(new WebhookResponse(200, TransportFailure.NONE, 12, Optional.empty(), Optional.empty()));

        useCase.attempt(command());

        ArgumentCaptor<Instant> claimInstant = ArgumentCaptor.forClass(Instant.class);
        verify(pipelinePort).claimForProcessing(eq(DELIVERY_ID), claimInstant.capture());

        ArgumentCaptor<WebhookRequest> requestCaptor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClientPort).send(requestCaptor.capture());

        ArgumentCaptor<AttemptOutcomeCommand> outcomeCaptor = ArgumentCaptor.forClass(AttemptOutcomeCommand.class);
        verify(outcomeWriter).write(outcomeCaptor.capture());

        assertThat(claimInstant.getValue()).isEqualTo(NOW);
        assertThat(requestCaptor.getValue().headers().get("X-Cobre-Timestamp")).isEqualTo(NOW.toString());
        assertThat(outcomeCaptor.getValue().attemptedAt()).isEqualTo(NOW);
    }

    private AttemptDeliveryUseCaseImpl newUseCase(RandomGenerator randomGenerator) {
        return new AttemptDeliveryUseCaseImpl(
                pipelinePort, subscriptionPort, eventPort, bulkheadPort, circuitBreakerPort, secretPort,
                serializerPort, webhookClientPort, urlValidator, outcomeWriter, workerProperties(),
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), randomGenerator);
    }

    private static AttemptDeliveryCommand command() {
        return new AttemptDeliveryCommand(DELIVERY_ID, SUBSCRIPTION_ID, 0, Optional.empty());
    }

    private static Delivery delivery() {
        return new Delivery(
                DELIVERY_ID, EVENT_ID, SUBSCRIPTION_ID, "client-1", DeliveryStatus.PROCESSING, DeliveryOrigin.INGEST,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), NOW, Optional.empty());
    }

    private static Subscription subscription() {
        return new Subscription(
                SUBSCRIPTION_ID, "client-1", "https://example.com/hook", SECRET_REF, Optional.empty(),
                Optional.empty(), Set.of("event.created"), true, VerificationState.VERIFIED, 10,
                CircuitState.CLOSED, Optional.empty());
    }

    private static Subscription subscriptionWithCircuitState(CircuitState state) {
        return new Subscription(
                SUBSCRIPTION_ID, "client-1", "https://example.com/hook", SECRET_REF, Optional.empty(),
                Optional.empty(), Set.of("event.created"), true, VerificationState.VERIFIED, 10,
                state, Optional.empty());
    }

    private static Subscription subscriptionWithPreviousSecret(Instant previousSecretExpiresAt) {
        return new Subscription(
                SUBSCRIPTION_ID, "client-1", "https://example.com/hook", SECRET_REF,
                Optional.of(PREVIOUS_SECRET_REF), Optional.of(previousSecretExpiresAt), Set.of("event.created"),
                true, VerificationState.VERIFIED, 10, CircuitState.CLOSED, Optional.empty());
    }

    private static Delivery deliveryWithAttemptCount(int attemptCount) {
        return new Delivery(
                DELIVERY_ID, EVENT_ID, SUBSCRIPTION_ID, "client-1", DeliveryStatus.PROCESSING, DeliveryOrigin.INGEST,
                Optional.empty(), attemptCount, Optional.empty(), Optional.empty(), Optional.empty(), NOW,
                Optional.empty());
    }

    private static NotificationEvent event() {
        return new NotificationEvent(EVENT_ID, "client-1", "event.created", "{}", NOW);
    }

    private static WorkerProperties workerProperties() {
        return new WorkerProperties(
                true, Duration.ofSeconds(20), 10, Duration.ofSeconds(2), Duration.ofSeconds(5),
                new WorkerProperties.Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
                new WorkerProperties.CircuitBreaker(10, Duration.ofSeconds(30), Duration.ofHours(1)),
                Duration.ofHours(1), 1024);
    }

    /**
     * A hand-written fake, not a mock: scenario 5 needs "the trip edge fires exactly once across
     * ten failures" to be obvious from the fake's own counting, not a stubbing puzzle built out of
     * {@code thenReturn(false, false, ..., true)}.
     */
    private static final class FakeCircuitBreakerPort implements CircuitBreakerPort {
        private final int tripOnCall;
        private int failureCalls = 0;

        FakeCircuitBreakerPort(int tripOnCall) {
            this.tripOnCall = tripOnCall;
        }

        @Override
        public void recordSuccess(UUID subscriptionId) {
        }

        @Override
        public boolean recordFailure(UUID subscriptionId) {
            failureCalls++;
            return failureCalls == tripOnCall;
        }

        @Override
        public void resetIfOpenLocally(UUID subscriptionId) {
        }
    }
}
