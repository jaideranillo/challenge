package com.cobre.challenge.adapter.out.persistence.cursor;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes the keyset cursor for {@code DeliveryQueryJdbcRepository.findPage}.
 *
 * <p>The cursor encodes the tuple {@code (Instant eventCreatedAt, UUID deliveryId)} as a
 * Base64-URL string (no padding). The plain-text form before encoding is:
 * <pre>
 *   {epochMicroseconds}:{deliveryId}
 * </pre>
 * where {@code epochMicroseconds} is {@link Instant#getEpochSecond()} * 1_000_000 +
 * {@link Instant#getNano()} / 1000, preserving PostgreSQL's microsecond precision.
 * The single {@code :} character separates the two fields unambiguously because the
 * epoch value is a decimal integer and the UUID uses only hex digits and hyphens.
 *
 * <p><strong>Opaque to the client, but not a security boundary.</strong> This cursor is
 * not signed and not encrypted, because it carries no secret and no authorization.
 * The tenant comes from the security context on every request, never from the cursor
 * itself. A cursor obtained as tenant A can be replayed by tenant B without granting any
 * access to tenant A's data — the mandatory {@code client_id} predicate in every
 * {@code findPage} call is the isolation guarantee, not the cursor encoding.
 *
 * <p>Decode is strict: a malformed, truncated or wrong-arity cursor throws
 * {@link MalformedCursorException}. It must not silently fall back to page 1 — a client
 * paging through a large result set and receiving page 1 back would loop forever, hiding
 * a real bug (A10).
 *
 * <p>Decoded values are parsed to {@link Instant} and {@link UUID} <em>before</em> being
 * bound to SQL parameters, so garbage input never reaches a {@code WHERE} clause as a raw
 * string (A05).
 *
 * <p>This record is immutable and stateless. Safe to share across virtual threads.
 */
public record DeliveryPageCursor(Instant eventCreatedAt, UUID deliveryId) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * Encodes the cursor to an opaque Base64-URL string.
     * Encoding is deterministic: the same {@code (eventCreatedAt, deliveryId)} pair always
     * produces the same string.
     */
    public String encode() {
        long epochMicros = toEpochMicros(eventCreatedAt);
        String plain = epochMicros + ":" + deliveryId;
        return ENCODER.encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Decodes an opaque cursor string back to its components.
     *
     * @throws MalformedCursorException if the string is not a valid cursor (not base64, wrong
     *                                   arity, unparseable timestamp or UUID, null, empty)
     */
    public static DeliveryPageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new MalformedCursorException("cursor is null or blank");
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new MalformedCursorException("cursor is not valid base64-url: " + e.getMessage());
        }
        String plain = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int colonIdx = plain.indexOf(':');
        if (colonIdx < 1 || colonIdx == plain.length() - 1) {
            throw new MalformedCursorException("cursor missing separator or empty field");
        }
        long epochMicros;
        try {
            epochMicros = Long.parseLong(plain.substring(0, colonIdx));
        } catch (NumberFormatException e) {
            throw new MalformedCursorException("cursor epoch is not a valid long: " + e.getMessage());
        }
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(plain.substring(colonIdx + 1));
        } catch (IllegalArgumentException e) {
            throw new MalformedCursorException("cursor delivery_id is not a valid UUID: " + e.getMessage());
        }
        Instant instant = fromEpochMicros(epochMicros);
        return new DeliveryPageCursor(instant, deliveryId);
    }

    /** Converts an {@link Instant} to epoch microseconds (Postgres precision). */
    public static long toEpochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    /** Converts epoch microseconds back to {@link Instant}, preserving microsecond precision. */
    public static Instant fromEpochMicros(long epochMicros) {
        long seconds = epochMicros / 1_000_000L;
        long nanos = (epochMicros % 1_000_000L) * 1_000L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    /**
     * Thrown when a cursor string cannot be decoded. Callers should surface this as a
     * client error (HTTP 400) — failing loudly is mandatory (A10).
     */
    public static final class MalformedCursorException extends RuntimeException {
        public MalformedCursorException(String message) {
            super(message);
        }
    }
}
