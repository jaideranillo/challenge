package com.cobre.challenge.application.port.in.pipeline.dto;

import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * @param status the status this call left the row in; {@code QUEUED} when this call changed
 *     nothing (a lost claim or a deferral)
 * @param outcome empty iff no HTTP attempt was made (lost claim, bulkhead deferral, open-circuit
 *     deferral); present in every other case, including pre-HTTP egress-validator classifications
 * @param eventId {@code Delivery.eventId()}, empty only when the claimed row itself could not be
 *     loaded (ADR-008 §5)
 * @param clientId {@code Delivery.clientId()}, same
 * @param attemptNumber the number actually attempted ({@code attemptCount + 1}), empty under the
 *     same condition as {@code eventId}; the caller falls back to the command's {@code
 *     attemptHint} when this is empty
 * @param httpResponseStatusCode the webhook response's status code; absent when no HTTP attempt
 *     was made (lost claim, bulkhead/circuit deferral, pre-HTTP egress rejection)
 */
public record AttemptDeliveryResult(
        DeliveryStatus status,
        Optional<AttemptOutcome> outcome,
        Optional<String> eventId,
        Optional<String> clientId,
        OptionalInt attemptNumber,
        OptionalInt httpResponseStatusCode) {

    public AttemptDeliveryResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(attemptNumber, "attemptNumber must not be null");
        Objects.requireNonNull(httpResponseStatusCode, "httpResponseStatusCode must not be null");
    }

    /** No row was ever loaded: a lost claim before or immediately after the claim query. */
    public static AttemptDeliveryResult noAttempt(DeliveryStatus status) {
        return new AttemptDeliveryResult(
                status, Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty());
    }

    /** No HTTP attempt was made, but the claimed row was loaded (a later short-circuit or a deferral). */
    public static AttemptDeliveryResult noAttempt(
            DeliveryStatus status, String eventId, String clientId, int attemptNumber) {
        return new AttemptDeliveryResult(
                status, Optional.empty(), Optional.of(eventId), Optional.of(clientId), OptionalInt.of(attemptNumber),
                OptionalInt.empty());
    }

    /** Convenience for a caller (a test double) that has no row to draw {@code eventId}/{@code clientId} from. */
    public static AttemptDeliveryResult of(DeliveryStatus status, AttemptOutcome outcome) {
        return new AttemptDeliveryResult(
                status, Optional.of(outcome), Optional.empty(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty());
    }

    /** An HTTP attempt was made (or a pre-HTTP classification stands in for one). */
    public static AttemptDeliveryResult of(
            DeliveryStatus status,
            AttemptOutcome outcome,
            String eventId,
            String clientId,
            int attemptNumber,
            OptionalInt httpResponseStatusCode) {
        return new AttemptDeliveryResult(
                status, Optional.of(outcome), Optional.of(eventId), Optional.of(clientId),
                OptionalInt.of(attemptNumber), httpResponseStatusCode);
    }
}
