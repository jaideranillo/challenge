package com.cobre.challenge.application.usecase;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.adapter.out.webhook.OutboundUrlValidator;
import com.cobre.challenge.adapter.out.webhook.dto.EgressVerdict;
import com.cobre.challenge.application.port.in.pipeline.AttemptDeliveryUseCase;
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
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.subscription.Subscription;
import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.ResponseClassifier;
import com.cobre.challenge.domain.policy.TransportFailure;
import com.cobre.challenge.domain.policy.WebhookSigner;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * The worker's per-message flow (ADR-002 SS2.2 steps 1-6). No {@code @Transactional} here: the
 * only transaction is {@link DeliveryOutcomeWriter}'s, opened and closed well after the HTTP call
 * returns. Step 7 ({@code DeleteMessage}) is the listener's (TASK-007-17): this method returns
 * normally on every business outcome (claim lost, deferred, or a recorded attempt) and throws
 * only on a genuine infrastructure failure the listener should not delete on.
 */
@Service
public class AttemptDeliveryUseCaseImpl implements AttemptDeliveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(AttemptDeliveryUseCaseImpl.class);

    private static final String MDC_DELIVERY_ID = "delivery_id";
    private static final String MDC_SUBSCRIPTION_ID = "subscription_id";
    private static final String MDC_ATTEMPT_NUMBER = "attempt_number";
    private static final String MDC_STATUS_CLASS = "status_class";
    private static final String MDC_TRACE_ID = "trace_id";

    private static final String HEADER_TIMESTAMP = "X-Cobre-Timestamp";
    private static final String HEADER_DELIVERY_ID = "X-Cobre-Delivery-Id";

    private static final String METRIC_EGRESS_REJECTED = "notification.webhook.egress.rejected";

    /**
     * Shared result for every claim-lost short-circuit (zero-row claim in step 1, or a delivery/
     * subscription/event that is absent in step 2 - the task file treats both as "the same
     * short-circuit"). No HTTP attempt was made, so {@code outcome} is empty.
     */
    private static final AttemptDeliveryResult CLAIM_LOST = AttemptDeliveryResult.noAttempt(DeliveryStatus.QUEUED);

    /**
     * Shared result for a deferral (bulkhead rejection in step 3, or an OPEN circuit in step 4).
     * The status is genuinely QUEUED ({@code deferDelivery} never changes status), and no HTTP
     * attempt was made, so {@code outcome} is empty.
     */
    private static final AttemptDeliveryResult DEFERRED = AttemptDeliveryResult.noAttempt(DeliveryStatus.QUEUED);

    private final DeliveryPipelineRepositoryPort pipelinePort;
    private final SubscriptionRepositoryPort subscriptionPort;
    private final NotificationEventRepositoryPort eventPort;
    private final BulkheadPort bulkheadPort;
    private final CircuitBreakerPort circuitBreakerPort;
    private final WebhookSecretPort secretPort;
    private final WebhookEnvelopeSerializerPort serializerPort;
    private final WebhookClientPort webhookClientPort;
    private final OutboundUrlValidator urlValidator;
    private final DeliveryOutcomeWriter outcomeWriter;
    private final WorkerProperties workerProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final RandomGenerator randomGenerator;

    public AttemptDeliveryUseCaseImpl(
            DeliveryPipelineRepositoryPort pipelinePort,
            SubscriptionRepositoryPort subscriptionPort,
            NotificationEventRepositoryPort eventPort,
            BulkheadPort bulkheadPort,
            CircuitBreakerPort circuitBreakerPort,
            WebhookSecretPort secretPort,
            WebhookEnvelopeSerializerPort serializerPort,
            WebhookClientPort webhookClientPort,
            OutboundUrlValidator urlValidator,
            DeliveryOutcomeWriter outcomeWriter,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry,
            Clock clock,
            RandomGenerator randomGenerator) {
        this.pipelinePort = pipelinePort;
        this.subscriptionPort = subscriptionPort;
        this.eventPort = eventPort;
        this.bulkheadPort = bulkheadPort;
        this.circuitBreakerPort = circuitBreakerPort;
        this.secretPort = secretPort;
        this.serializerPort = serializerPort;
        this.webhookClientPort = webhookClientPort;
        this.urlValidator = urlValidator;
        this.outcomeWriter = outcomeWriter;
        this.workerProperties = workerProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.randomGenerator = randomGenerator;
    }

    @Override
    public AttemptDeliveryResult attempt(AttemptDeliveryCommand command) {
        MDC.put(MDC_DELIVERY_ID, command.deliveryId().toString());
        MDC.put(MDC_SUBSCRIPTION_ID, command.subscriptionId().toString());
        try {
            return doAttempt(command);
        } finally {
            MDC.clear();
        }
    }

    private AttemptDeliveryResult doAttempt(AttemptDeliveryCommand command) {
        UUID deliveryId = command.deliveryId();
        UUID subscriptionId = command.subscriptionId();
        Instant now = Instant.now(clock);

        // Step 1: conditional claim. Zero rows is a normal duplicate/race outcome; never load further.
        if (!pipelinePort.claimForProcessing(deliveryId, now)) {
            meterRegistry.counter("notification.delivery.claim.lost").increment();
            log.info("Claim lost: zero rows affected by claimForProcessing");
            return CLAIM_LOST;
        }

        // Step 2: load what was just claimed. Any absence here is the same short-circuit as step 1.
        Optional<Delivery> deliveryOpt = pipelinePort.findById(deliveryId);
        if (deliveryOpt.isEmpty()) {
            log.warn("Claimed delivery could not be loaded");
            return CLAIM_LOST;
        }
        Delivery delivery = deliveryOpt.get();

        Optional<Subscription> subscriptionOpt = subscriptionPort.findById(subscriptionId);
        if (subscriptionOpt.isEmpty()) {
            log.warn("Subscription not found for a claimed delivery");
            return CLAIM_LOST;
        }
        Subscription subscription = subscriptionOpt.get();

        Optional<NotificationEvent> eventOpt = eventPort.findById(delivery.eventId());
        if (eventOpt.isEmpty()) {
            log.warn("Notification event not found for a claimed delivery");
            return CLAIM_LOST;
        }
        NotificationEvent event = eventOpt.get();

        if (subscription.circuitState() == CircuitState.CLOSED) {
            circuitBreakerPort.resetIfOpenLocally(subscriptionId);
        }

        int attemptNumber = delivery.attemptCount() + 1;
        MDC.put(MDC_ATTEMPT_NUMBER, String.valueOf(attemptNumber));
        command.traceparent().or(delivery::traceContext).ifPresent(traceId -> MDC.put(MDC_TRACE_ID, traceId));

        // Step 3: bulkhead permit.
        WorkerProperties.Bulkhead bulkheadConfig = workerProperties.bulkhead();
        if (!bulkheadPort.tryAcquire(subscriptionId, subscription.maxConcurrency(), bulkheadConfig.acquireTimeout())) {
            deferWithJitter(deliveryId, now, bulkheadConfig, "notification.delivery.bulkhead.deferred");
            return DEFERRED;
        }

        try {
            // Step 4: circuit gate. OPEN defers exactly as step 3; HALF_OPEN proceeds as the probe.
            if (subscription.circuitState() == CircuitState.OPEN) {
                deferWithJitter(deliveryId, now, bulkheadConfig, "notification.delivery.circuit.deferred");
                return DEFERRED;
            }
            boolean isProbe = subscription.circuitState() == CircuitState.HALF_OPEN;

            return attemptSend(delivery, subscription, event, attemptNumber, isProbe, now);
        } finally {
            bulkheadPort.release(subscriptionId);
        }
    }

    private void deferWithJitter(
            UUID deliveryId, Instant now, WorkerProperties.Bulkhead bulkheadConfig, String counterName) {
        meterRegistry.counter(counterName).increment();
        Instant deferredUntil = now.plus(jitter(bulkheadConfig.deferMin(), bulkheadConfig.deferMax()));
        if (!pipelinePort.deferDelivery(deliveryId, deferredUntil)) {
            log.debug("Deferral write affected no row");
        }
        log.info("Delivery deferred");
    }

    /** Uniform draw over [min, max], the same shape the merged {@code RetryPolicy} uses. */
    private Duration jitter(Duration min, Duration max) {
        long minNanos = min.toNanos();
        long spanNanos = max.toNanos() - minNanos;
        long drawnNanos = spanNanos > 0 ? (long) (randomGenerator.nextDouble() * spanNanos) : 0L;
        return Duration.ofNanos(minNanos + drawnNanos);
    }

    private AttemptDeliveryResult attemptSend(
            Delivery delivery,
            Subscription subscription,
            NotificationEvent event,
            int attemptNumber,
            boolean isProbe,
            Instant now) {

        // Step 5: build, sign and send.
        WebhookEnvelope envelope = new WebhookEnvelope(
                delivery.deliveryId(), event.eventId(), event.eventType(), event.clientId(), event.createdAt(),
                attemptNumber, event.content());
        String body = serializerPort.serialize(envelope);
        String timestamp = now.toString();

        Optional<String> secret = secretPort.resolve(subscription.secretRef());
        if (secret.isEmpty()) {
            log.warn("Secret unresolved; failing closed, no request sent");
            return recordAndReturn(new AttemptOutcomeCommand(
                    delivery.deliveryId(), subscription.subscriptionId(), attemptNumber, now,
                    AttemptOutcome.NON_RETRYABLE, OptionalInt.empty(), TransportFailure.NONE, Optional.empty(), 0,
                    Optional.of("secret unresolved"), Optional.empty(), isProbe, delivery.attemptCount()));
        }

        Optional<String> previousSecret = resolvePreviousSecret(subscription, now);

        Map<String, String> headers =
                new LinkedHashMap<>(WebhookSigner.sign(body, timestamp, secret.get(), previousSecret));
        headers.put(HEADER_TIMESTAMP, timestamp);
        headers.put(HEADER_DELIVERY_ID, delivery.deliveryId().toString());

        // Step 5 (pre-flight): validate the target on every attempt, right before the POST - no
        // cached verdict, which is the DNS-rebinding defense (OutboundUrlValidator's javadoc).
        Instant validationStart = Instant.now(clock);
        EgressVerdict verdict = urlValidator.validate(subscription.targetUrl());
        int validationTimeMs = (int) Duration.between(validationStart, Instant.now(clock)).toMillis();

        if (verdict.state() != EgressVerdict.State.ALLOWED) {
            return handleEgressRejection(delivery, subscription, attemptNumber, isProbe, now, verdict, validationTimeMs);
        }

        WebhookResponse response = webhookClientPort.send(new WebhookRequest(subscription.targetUrl(), body, headers));

        // Step 6: classify, breaker bookkeeping, then the single-transaction write.
        AttemptOutcome outcome = ResponseClassifier.classify(response.statusCode(), response.failure());
        applyBreakerBookkeeping(outcome, subscription, isProbe, now);

        OptionalInt httpStatus = response.failure() == TransportFailure.NONE
                ? OptionalInt.of(response.statusCode())
                : OptionalInt.empty();

        return recordAndReturn(new AttemptOutcomeCommand(
                delivery.deliveryId(), subscription.subscriptionId(), attemptNumber, now, outcome, httpStatus,
                response.failure(), Optional.empty(), response.responseTimeMs(), response.responseExcerpt(),
                response.retryAfter(), isProbe, delivery.attemptCount()));
    }

    /**
     * DNS_FAILURE -> RETRYABLE (ordinary transient noise, counts toward the breaker like any
     * other DNS failure). POLICY_REJECTED -> NON_RETRYABLE (a possible SSRF/rebinding attempt:
     * never touches the breaker, raises the security signal instead). Neither opens a socket.
     */
    private AttemptDeliveryResult handleEgressRejection(
            Delivery delivery,
            Subscription subscription,
            int attemptNumber,
            boolean isProbe,
            Instant now,
            EgressVerdict verdict,
            int elapsedMs) {
        AttemptOutcome outcome = verdict.state() == EgressVerdict.State.DNS_FAILURE
                ? AttemptOutcome.RETRYABLE
                : AttemptOutcome.NON_RETRYABLE;

        applyBreakerBookkeeping(outcome, subscription, isProbe, now);

        if (verdict.state() == EgressVerdict.State.POLICY_REJECTED) {
            meterRegistry.counter(METRIC_EGRESS_REJECTED).increment();
            if (verdict.resolvedAddress().isPresent()) {
                log.warn(
                        "Egress policy rejected target host={} reason={} resolvedAddress={}",
                        targetHostOf(subscription), verdict.reason(), verdict.resolvedAddress().get());
            } else {
                log.warn("Egress policy rejected target host={} reason={}", targetHostOf(subscription), verdict.reason());
            }
        }

        return recordAndReturn(new AttemptOutcomeCommand(
                delivery.deliveryId(), subscription.subscriptionId(), attemptNumber, now, outcome,
                OptionalInt.empty(), TransportFailure.NONE, Optional.of(verdict.reason()), elapsedMs, Optional.empty(),
                Optional.empty(), isProbe, delivery.attemptCount()));
    }

    private void applyBreakerBookkeeping(AttemptOutcome outcome, Subscription subscription, boolean isProbe, Instant now) {
        if (outcome.countsTowardCircuitBreaker() && !isProbe && subscription.circuitState() == CircuitState.CLOSED) {
            if (circuitBreakerPort.recordFailure(subscription.subscriptionId())) {
                meterRegistry.counter("notification.circuit.transition", "direction", "CLOSED_TO_OPEN").increment();
                WorkerProperties.CircuitBreaker circuitBreaker = workerProperties.circuitBreaker();
                boolean tripped = subscriptionPort.tripCircuit(
                        subscription.subscriptionId(), circuitBreaker.baseCooldown(), circuitBreaker.maxCooldown(), now);
                if (!tripped) {
                    log.debug("tripCircuit affected no row");
                }
            }
        }
        if (outcome == AttemptOutcome.SUCCESS) {
            circuitBreakerPort.recordSuccess(subscription.subscriptionId());
        }
    }

    /**
     * Host only, never the full target URL (the "never log the target URL" rule) - this is the
     * one narrowly scoped exception the security signal needs (A01, A09).
     */
    private static String targetHostOf(Subscription subscription) {
        try {
            String host = new URI(subscription.targetUrl()).getHost();
            return host != null ? host : "unknown";
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    /** Present only when the previous secret is still within its grace window (ADR-004 SS2/SS3). */
    private Optional<String> resolvePreviousSecret(Subscription subscription, Instant now) {
        boolean stillValid = subscription.previousSecretExpiresAt()
                .map(expiresAt -> expiresAt.isAfter(now))
                .orElse(false);
        if (subscription.previousSecretRef().isPresent() && stillValid) {
            return secretPort.resolve(subscription.previousSecretRef().get());
        }
        return Optional.empty();
    }

    private AttemptDeliveryResult recordAndReturn(AttemptOutcomeCommand outcomeCommand) {
        outcomeWriter.write(outcomeCommand);
        AttemptOutcome outcome = outcomeCommand.outcome();
        MDC.put(MDC_STATUS_CLASS, outcome.name());
        meterRegistry.counter("notification.delivery.attempt", "outcome", outcome.name()).increment();
        log.info("Attempt outcome={}", outcome);
        return AttemptDeliveryResult.of(nominalStatusFor(outcome), outcome);
    }

    /**
     * A nominal, best-effort status label for the caller/telemetry only. Whether a RETRYABLE
     * outcome actually became RETRYING or DEAD (schedule exhausted) is {@link DeliveryOutcomeWriter}
     * and {@code RetryPolicy}'s decision alone - not reimplemented here.
     */
    private static DeliveryStatus nominalStatusFor(AttemptOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> DeliveryStatus.DELIVERED;
            case RETRYABLE, RETRYABLE_THROTTLED -> DeliveryStatus.RETRYING;
            case NON_RETRYABLE, NON_RETRYABLE_REDIRECT, NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION -> DeliveryStatus.DEAD;
        };
    }
}
