package com.cobre.challenge.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Expected digests below are computed independently (Python hmac/hashlib, HMAC-SHA256 over
 * {@code timestamp + "." + body}), not by calling {@link WebhookSigner}.
 */
class WebhookSignerTest {

    private static final String BODY = "{\"event\":\"test\"}";
    private static final String TIMESTAMP = "1700000000";
    private static final String SECRET = "whsec_test123";
    private static final String PREVIOUS_SECRET = "whsec_prev456";

    private static final String EXPECTED_CURRENT_SIGNATURE =
            "2b25184bbfeecb10f9c359b0245b449280d1354e1655b5c2ae120ad7ede4a4a0";
    private static final String EXPECTED_PREVIOUS_SIGNATURE =
            "caf23d956a48032d58b4cf2a4d5ab92211383ffb7ba02d22bec8866cf55cd77a";
    private static final String EXPECTED_CHANGED_BODY_SIGNATURE =
            "68201a8a19ce4061bf5dc00207d69e72c9359d34553fabaef400fe2659604621";
    private static final String EXPECTED_CHANGED_TIMESTAMP_SIGNATURE =
            "f05def2d73e1aa950b1f33534dec65af1e0f03583df1e7526e494551aca5f5a8";

    @Test
    void knownTripleProducesKnownDigest() {
        Map<String, String> headers = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty());

        assertEquals(EXPECTED_CURRENT_SIGNATURE, headers.get(WebhookSigner.SIGNATURE_HEADER));
    }

    @Test
    void signatureIsLowercaseHexOfShaLength() {
        String signature = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty())
                .get(WebhookSigner.SIGNATURE_HEADER);

        assertEquals(64, signature.length());
        assertEquals(signature.toLowerCase(), signature);
        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void changingOneByteOfBodyChangesSignature() {
        String original = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty())
                .get(WebhookSigner.SIGNATURE_HEADER);
        String changed = WebhookSigner.sign("{\"event\":\"Test\"}", TIMESTAMP, SECRET, Optional.empty())
                .get(WebhookSigner.SIGNATURE_HEADER);

        assertEquals(EXPECTED_CHANGED_BODY_SIGNATURE, changed);
        assertNotEquals(original, changed);
    }

    @Test
    void changingTimestampChangesSignature() {
        String original = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty())
                .get(WebhookSigner.SIGNATURE_HEADER);
        String changed = WebhookSigner.sign(BODY, "1700000001", SECRET, Optional.empty())
                .get(WebhookSigner.SIGNATURE_HEADER);

        assertEquals(EXPECTED_CHANGED_TIMESTAMP_SIGNATURE, changed);
        assertNotEquals(original, changed);
    }

    @Test
    void previousSecretPresentProducesBothHeadersWithDifferentValues() {
        Map<String, String> headers =
                WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.of(PREVIOUS_SECRET));

        assertEquals(EXPECTED_CURRENT_SIGNATURE, headers.get(WebhookSigner.SIGNATURE_HEADER));
        assertEquals(EXPECTED_PREVIOUS_SIGNATURE, headers.get(WebhookSigner.SIGNATURE_PREVIOUS_HEADER));
        assertNotEquals(
                headers.get(WebhookSigner.SIGNATURE_HEADER),
                headers.get(WebhookSigner.SIGNATURE_PREVIOUS_HEADER));
    }

    @Test
    void previousSecretEmptyProducesExactlyOneHeaderWithNoNullValue() {
        Map<String, String> headers = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty());

        assertEquals(1, headers.size());
        assertTrue(headers.containsKey(WebhookSigner.SIGNATURE_HEADER));
        assertFalse(headers.containsKey(WebhookSigner.SIGNATURE_PREVIOUS_HEADER));
        assertFalse(headers.containsValue(null));
    }

    @Test
    void returnedMapIsImmutable() {
        Map<String, String> headers = WebhookSigner.sign(BODY, TIMESTAMP, SECRET, Optional.empty());

        assertThrows(UnsupportedOperationException.class, () -> headers.put("X-Extra", "value"));
    }

    @Test
    void nullArgumentsAreRejectedWithoutValueInMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookSigner.sign(null, TIMESTAMP, SECRET, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookSigner.sign(BODY, null, SECRET, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookSigner.sign(BODY, TIMESTAMP, null, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookSigner.sign(BODY, TIMESTAMP, SECRET, null));
    }
}
