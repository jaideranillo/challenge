package com.cobre.challenge.domain.policy;

import java.util.Optional;

/**
 * ADR-008 §3.3: the {@code toString()} layer that keeps {@code content}/{@code response_excerpt}
 * out of a log line even when a type is logged whole (e.g. {@code log.info("processing {}", event)}).
 * Prints a marker and a length, never the value, never a hash.
 */
public final class Redaction {

    private static final String MARKER = "[REDACTED length=%d]";

    private Redaction() {
    }

    public static String redact(String value) {
        return MARKER.formatted(value == null ? 0 : value.length());
    }

    public static String redact(Optional<String> value) {
        return MARKER.formatted(value.map(String::length).orElse(0));
    }
}
