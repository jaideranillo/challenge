package com.cobre.challenge.application.usecase;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.SubscriptionRepositoryPort;
import com.cobre.challenge.application.usecase.dto.AttemptOutcomeCommand;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.RetryPolicy;
import com.cobre.challenge.domain.policy.TransportFailure;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 6's single transaction (ADR-002 SS2.2): the attempt row, the delivery row, and (for a
 * probe) the circuit transition, committed as a unit. Separate bean so {@code @Transactional}
 * proxying can't be bypassed by self-invocation (same reason as {@link RelayBatchClaimer}).
 *
 * <p>No HTTP, no queue, no classification: {@link AttemptOutcomeCommand#outcome()} arrives
 * already computed and this class only switches on it.
 */
@Component
public class DeliveryOutcomeWriter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeWriter.class);

    /** {@code delivery_attempts.error} / {@code deliveries.last_error} defensive truncation bound. */
    private static final int ERROR_MAX = 1000;

    private final DeliveryAttemptRepositoryPort attemptPort;
    private final DeliveryPipelineRepositoryPort pipelinePort;
    private final SubscriptionRepositoryPort subscriptionPort;
    private final RetryPolicy retryPolicy;
    private final WorkerProperties workerProperties;

    public DeliveryOutcomeWriter(
            DeliveryAttemptRepositoryPort attemptPort,
            DeliveryPipelineRepositoryPort pipelinePort,
            SubscriptionRepositoryPort subscriptionPort,
            RetryPolicy retryPolicy,
            WorkerProperties workerProperties) {
        this.attemptPort = attemptPort;
        this.pipelinePort = pipelinePort;
        this.subscriptionPort = subscriptionPort;
        this.retryPolicy = retryPolicy;
        this.workerProperties = workerProperties;
    }

    @Transactional
    public void write(AttemptOutcomeCommand command) {
        String error = resolveError(command);

        attemptPort.insert(new DeliveryAttempt(
                command.deliveryId(),
                command.attemptNumber(),
                command.httpStatus(),
                command.responseTimeMs(),
                command.responseExcerpt(),
                command.outcome() == AttemptOutcome.SUCCESS ? Optional.empty() : Optional.of(error),
                command.attemptedAt()));

        applyDeliveryOutcome(command, error);

        if (command.wasHalfOpenProbe()) {
            applyProbeCircuitTransition(command);
        }
    }

    private void applyDeliveryOutcome(AttemptOutcomeCommand command, String error) {
        switch (command.outcome()) {
            case SUCCESS -> tolerate(pipelinePort.markDelivered(command.deliveryId(), command.attemptedAt()));
            case RETRYABLE -> retryOrDie(command, error);
            case RETRYABLE_THROTTLED -> {
                Optional<Instant> nextAttemptAt = retryOrDie(command, error);
                applyThrottle(command, nextAttemptAt);
            }
            case NON_RETRYABLE, NON_RETRYABLE_REDIRECT -> markDead(command, error);
            case NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION -> {
                markDead(command, error);
                tolerate(subscriptionPort.deactivate(command.subscriptionId()));
            }
        }
    }

    /** Returns the schedule slot used, present when a retry was scheduled, empty when the delivery was marked dead. */
    private Optional<Instant> retryOrDie(AttemptOutcomeCommand command, String error) {
        Optional<Instant> nextAttemptAt =
                retryPolicy.nextAttemptAt(command.currentAttemptCount() + 1, command.attemptedAt());
        if (nextAttemptAt.isPresent()) {
            tolerate(pipelinePort.scheduleRetry(
                    command.deliveryId(), nextAttemptAt.get(), error, command.attemptedAt()));
        } else {
            markDead(command, error);
        }
        return nextAttemptAt;
    }

    private void applyThrottle(AttemptOutcomeCommand command, Optional<Instant> nextAttemptAt) {
        Optional<Instant> throttledUntil = command.retryAfter()
                .map(command.attemptedAt()::plus)
                .or(() -> nextAttemptAt);
        throttledUntil.ifPresent(until -> tolerate(subscriptionPort.setThrottledUntil(command.subscriptionId(), until)));
    }

    private void markDead(AttemptOutcomeCommand command, String error) {
        tolerate(pipelinePort.markDead(command.deliveryId(), error, command.attemptedAt()));
    }

    private void applyProbeCircuitTransition(AttemptOutcomeCommand command) {
        AttemptOutcome outcome = command.outcome();
        if (outcome == AttemptOutcome.SUCCESS) {
            tolerate(subscriptionPort.closeCircuit(command.subscriptionId(), command.attemptedAt()));
        } else if (outcome.countsTowardCircuitBreaker()) {
            WorkerProperties.CircuitBreaker circuitBreaker = workerProperties.circuitBreaker();
            tolerate(subscriptionPort.reopenCircuit(
                    command.subscriptionId(),
                    circuitBreaker.baseCooldown(),
                    circuitBreaker.maxCooldown(),
                    command.attemptedAt()));
        }
    }

    /** Never PII: the status code or the {@link TransportFailure} name, nothing from the response body, URL, or headers. */
    private static String describeFailure(AttemptOutcomeCommand command) {
        if (command.httpStatus().isPresent()) {
            return "HTTP " + command.httpStatus().getAsInt();
        }
        if (command.transportFailure() != TransportFailure.NONE) {
            return command.transportFailure().name();
        }
        return command.outcome().name();
    }

    /** A non-blank explicit {@code command.error()} wins over the derived fallback (addendum 12a). */
    private static String resolveError(AttemptOutcomeCommand command) {
        String explicit = command.error().orElse(null);
        String resolved = explicit != null && !explicit.isBlank() ? explicit : describeFailure(command);
        return truncate(resolved);
    }

    private static String truncate(String value) {
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }

    /** A false conditional write is a lost race, not an error: log and move on, never throw or retry the write. */
    private static void tolerate(boolean written) {
        if (!written) {
            log.debug("Conditional write affected no row");
        }
    }
}
