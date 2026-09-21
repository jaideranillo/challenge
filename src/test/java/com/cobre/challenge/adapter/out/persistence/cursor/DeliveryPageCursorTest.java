package com.cobre.challenge.adapter.out.persistence.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain JUnit tests for {@link DeliveryPageCursor}. No Spring context needed.
 */
class DeliveryPageCursorTest {

    /** Test 1: round trip preserves Instant at microsecond precision. */
    @Test
    void roundTrip_preservesMicrosecondPrecision() {
        // Instant with microseconds (truncated from nanos to match Postgres precision)
        Instant ts = Instant.parse("2026-09-20T15:07:00.123456Z");
        UUID id = UUID.randomUUID();

        DeliveryPageCursor cursor = new DeliveryPageCursor(ts, id);
        String encoded = cursor.encode();
        DeliveryPageCursor decoded = DeliveryPageCursor.decode(encoded);

        assertThat(decoded.eventCreatedAt()).isEqualTo(ts);
        assertThat(decoded.deliveryId()).isEqualTo(id);
    }

    /** Test 1b: microsecond precision — a codec that truncates to seconds would fail. */
    @Test
    void roundTrip_microsecondPrecision_notTruncatedToSeconds() {
        Instant ts1 = Instant.parse("2026-09-20T15:07:00.000001Z"); // 1 microsecond
        Instant ts2 = Instant.parse("2026-09-20T15:07:00.000002Z"); // 2 microseconds
        UUID id = UUID.randomUUID();

        String enc1 = new DeliveryPageCursor(ts1, id).encode();
        String enc2 = new DeliveryPageCursor(ts2, id).encode();

        assertThat(DeliveryPageCursor.decode(enc1).eventCreatedAt()).isEqualTo(ts1);
        assertThat(DeliveryPageCursor.decode(enc2).eventCreatedAt()).isEqualTo(ts2);
        // The two cursors must be different (no truncation to seconds)
        assertThat(enc1).isNotEqualTo(enc2);
    }

    /** Test 2: malformed inputs all throw, none return a default cursor. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-base64!!!", "YWJj", "MA=="})
    void decode_malformedInput_throwsMalformedCursorException(String bad) {
        assertThatThrownBy(() -> DeliveryPageCursor.decode(bad))
                .isInstanceOf(DeliveryPageCursor.MalformedCursorException.class);
    }

    @Test
    void decode_null_throws() {
        assertThatThrownBy(() -> DeliveryPageCursor.decode(null))
                .isInstanceOf(DeliveryPageCursor.MalformedCursorException.class);
    }

    @Test
    void decode_rightArityWrongTimestamp_throws() {
        // base64url of "notanumber:550e8400-e29b-41d4-a716-446655440000"
        String plain = "notanumber:550e8400-e29b-41d4-a716-446655440000";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DeliveryPageCursor.decode(encoded))
                .isInstanceOf(DeliveryPageCursor.MalformedCursorException.class);
    }

    @Test
    void decode_rightArityWrongUuid_throws() {
        String plain = "1234567890:not-a-valid-uuid";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DeliveryPageCursor.decode(encoded))
                .isInstanceOf(DeliveryPageCursor.MalformedCursorException.class);
    }

    /** Test 3: encoding is stable (deterministic). */
    @Test
    void encode_deterministic_samePairProducesSameString() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        DeliveryPageCursor c = new DeliveryPageCursor(ts, id);
        assertThat(c.encode()).isEqualTo(c.encode());
    }
}
