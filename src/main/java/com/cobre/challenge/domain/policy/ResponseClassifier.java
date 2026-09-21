package com.cobre.challenge.domain.policy;

/**
 * Pure classification of one delivery attempt's raw result into a domain
 * {@link AttemptOutcome} (ADR-004 SS1). Stateless: no field, no Spring bean.
 */
public final class ResponseClassifier {

    private ResponseClassifier() {
    }

    public static AttemptOutcome classify(int statusCode, TransportFailure failure) {
        if (failure != TransportFailure.NONE) {
            return AttemptOutcome.RETRYABLE;
        }

        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException(
                    "statusCode must be in [100, 599] when no transport failure occurred, was " + statusCode);
        }

        if (statusCode >= 200 && statusCode <= 299) {
            return AttemptOutcome.SUCCESS;
        }
        if (statusCode >= 300 && statusCode <= 399) {
            return AttemptOutcome.NON_RETRYABLE_REDIRECT;
        }
        if (statusCode == 404 || statusCode == 410) {
            return AttemptOutcome.NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION;
        }
        if (statusCode == 408) {
            return AttemptOutcome.RETRYABLE;
        }
        if (statusCode == 429) {
            return AttemptOutcome.RETRYABLE_THROTTLED;
        }
        if (statusCode >= 400 && statusCode <= 499) {
            // ADR-004 SS1 names 400/401/403/422 explicitly as NON_RETRYABLE and treats every
            // other non-2xx/408/429 4xx as a permanent/business failure. This branch is the
            // deliberate default for the remaining 4xx codes (e.g. 418, 451), not a gap.
            return AttemptOutcome.NON_RETRYABLE;
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return AttemptOutcome.RETRYABLE;
        }

        throw new IllegalArgumentException("Unclassifiable statusCode " + statusCode);
    }
}
