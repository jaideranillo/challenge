package com.cobre.challenge.domain.policy;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure HMAC-SHA256 signing of one webhook delivery attempt (ADR-004 SS2). Signing string is
 * {@code timestampHeaderValue + "." + body}, hex-encoded lowercase. No I/O, no Spring, no clock,
 * no logging. Signs only; never verifies (YAGNI, see task).
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    public static final String SIGNATURE_HEADER = "X-Cobre-Signature";
    public static final String SIGNATURE_PREVIOUS_HEADER = "X-Cobre-Signature-Previous";

    private WebhookSigner() {
    }

    public static Map<String, String> sign(
            String body, String timestampHeaderValue, String currentSecret, Optional<String> previousSecret) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (timestampHeaderValue == null) {
            throw new IllegalArgumentException("timestampHeaderValue must not be null");
        }
        if (currentSecret == null) {
            throw new IllegalArgumentException("currentSecret must not be null");
        }
        if (previousSecret == null) {
            throw new IllegalArgumentException("previousSecret must not be null");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SIGNATURE_HEADER, hmac(body, timestampHeaderValue, currentSecret));
        if (previousSecret.isPresent()) {
            headers.put(SIGNATURE_PREVIOUS_HEADER, hmac(body, timestampHeaderValue, previousSecret.get()));
        }
        return Map.copyOf(headers);
    }

    private static String hmac(String body, String timestampHeaderValue, String secret) {
        String signingString = timestampHeaderValue + "." + body;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }
}
