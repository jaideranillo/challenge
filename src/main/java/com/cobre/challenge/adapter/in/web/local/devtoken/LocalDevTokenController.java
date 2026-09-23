package com.cobre.challenge.adapter.in.web.local.devtoken;

import com.cobre.challenge.adapter.in.web.local.devtoken.dto.DevTokenRequest;
import com.cobre.challenge.adapter.in.web.local.devtoken.dto.DevTokenResponse;
import com.cobre.challenge.adapter.in.web.security.config.JwtProperties;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Mints a test JWT signed with the dev key pair in {@code tools/dev-jwt/}, over HTTP, so a token
 * can be obtained from Insomnia (or any HTTP client) without a shell. This is now the sole "local
 * helper" ADR-007 §7 calls for ("a helper script... or equivalent") - the shell-script form
 * (`tools/dev-jwt/issue-token.sh`) was removed once this endpoint existed, since the two would
 * otherwise be two divergent definitions of what a valid local token looks like. What that script
 * covered and this endpoint does not: negative-test tokens (wrong audience, expired, missing
 * {@code client_id}) - those live in the automated test suite only now (e.g.
 * {@code JwtClaimValidatorsTest}), never in either local tool.
 *
 * <p>Reachable only because of {@link LocalDevTokenSecurityConfig}, {@code local} profile only,
 * mirroring {@code EventGeneratorController}. The private key it reads
 * ({@code tools/dev-jwt/dev-private.pem}) is the same publicly-known, no-security-value key the
 * script uses - outside {@code src/main/resources}, so it is never in the built artifact
 * regardless of which profile is active (ADR-007 §7).
 */
@RestController
@RequestMapping("/local/dev-token")
class LocalDevTokenController {

    private static final Path PRIVATE_KEY_PATH = Path.of("tools/dev-jwt/dev-private.pem");
    private static final String HEADER_JSON = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long maxLifetimeSeconds;
    private final ObjectMapper objectMapper;

    LocalDevTokenController(
            OAuth2ResourceServerProperties resourceServerProperties,
            JwtProperties jwtProperties,
            ObjectMapper objectMapper)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        this.issuer = resourceServerProperties.getJwt().getIssuerUri();
        this.audience = jwtProperties.audience();
        this.maxLifetimeSeconds = jwtProperties.maxLifetime().toSeconds();
        this.objectMapper = objectMapper;
        this.privateKey = loadPrivateKey(PRIVATE_KEY_PATH);
    }

    @PostMapping
    ResponseEntity<DevTokenResponse> issue(@Valid @RequestBody DevTokenRequest request) throws SignatureException {
        long lifetime = Math.min(request.lifetimeSeconds().orElse(maxLifetimeSeconds), maxLifetimeSeconds);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(lifetime);
        String subject = request.subject().orElse(request.clientId());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("iat", now.getEpochSecond());
        claims.put("sub", subject);
        claims.put("client_id", request.clientId());
        claims.put("scope", request.scope());

        String token = sign(claims);
        return ResponseEntity.ok(new DevTokenResponse(token, request.clientId(), request.scope(), expiresAt));
    }

    private String sign(Map<String, Object> claims) throws SignatureException {
        String headerB64 = base64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64Url(objectMapper.writeValueAsBytes(claims));
        String signingInput = headerB64 + "." + payloadB64;

        Signature signature;
        try {
            signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize dev token signature", e);
        }
        String signatureB64 = base64Url(signature.sign());
        return signingInput + "." + signatureB64;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PrivateKey loadPrivateKey(Path path) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
