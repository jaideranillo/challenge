package com.cobre.challenge.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Unit tests for ADR-007 §3's token contract and §4's authority mapping. Plain JUnit against
 * hand-built {@link Jwt}s and the exact validator/converter composition
 * {@code JwtDecoderConfig} assembles, so this proves the effective decoder behavior without a
 * Spring context.
 */
class JwtClaimValidatorsTest {

    private static final String ISSUER = "http://localhost:8080/issuer/challenge-local";
    private static final String AUDIENCE = "challenge-api";
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration MAX_LIFETIME = Duration.ofHours(1);

    // --- Individual claim validators ---

    @Test
    void acceptsAValidToken() {
        assertValid(fullValidator().validate(validJwtBuilder().build()));
    }

    @Test
    void rejectsWrongIssuer() {
        Jwt jwt = validJwtBuilder().issuer("http://attacker.example/issuer").build();
        assertInvalid(fullValidator().validate(jwt));
    }

    @Test
    void rejectsWrongAudience() {
        Jwt jwt = validJwtBuilder().audience(List.of("some-other-service")).build();
        assertInvalid(JwtClaimValidators.audience(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsMissingAudience() {
        Jwt jwt = jwtWithoutClaim("aud");
        assertInvalid(JwtClaimValidators.audience(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsExpiredBeyondSkew() {
        Instant now = Instant.now();
        Jwt jwt = validJwtBuilder()
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(31))
                .build();
        assertInvalid(new JwtTimestampValidator(SKEW).validate(jwt));
    }

    @Test
    void acceptsExpiredWithinSkew() {
        Instant now = Instant.now();
        Jwt jwt = validJwtBuilder()
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(10))
                .build();
        assertValid(new JwtTimestampValidator(SKEW).validate(jwt));
    }

    @Test
    void rejectsIatInTheFuture() {
        Instant iat = Instant.now().plusSeconds(120);
        Jwt jwt = jwt(Map.of("iat", iat, "exp", iat.plusSeconds(60)));
        assertInvalid(JwtClaimValidators.issuedAtNotInFuture(SKEW).validate(jwt));
    }

    @Test
    void acceptsIatWithinSkewOfNow() {
        Jwt jwt = jwtWithClaim("iat", Instant.now().plusSeconds(15));
        assertValid(JwtClaimValidators.issuedAtNotInFuture(SKEW).validate(jwt));
    }

    @Test
    void rejectsLifetimeOverTheCeiling() {
        Instant iat = Instant.now().minusSeconds(30);
        Jwt jwt = jwt(Map.of("iat", iat, "exp", iat.plus(MAX_LIFETIME).plusSeconds(1)));
        assertInvalid(JwtClaimValidators.maxLifetime(MAX_LIFETIME).validate(jwt));
    }

    @Test
    void acceptsLifetimeAtExactlyTheCeiling() {
        Instant iat = Instant.now().minusSeconds(30);
        Jwt jwt = jwt(Map.of("iat", iat, "exp", iat.plus(MAX_LIFETIME)));
        assertValid(JwtClaimValidators.maxLifetime(MAX_LIFETIME).validate(jwt));
    }

    @Test
    void rejectsMissingSubject() {
        Jwt jwt = jwtWithoutClaim("sub");
        assertInvalid(JwtClaimValidators.subject().validate(jwt));
    }

    @Test
    void rejectsMissingClientId() {
        Jwt jwt = jwtWithoutClaim("client_id");
        assertInvalid(JwtClaimValidators.clientId().validate(jwt));
    }

    @Test
    void rejectsBlankClientId() {
        Jwt jwt = jwtWithClaim("client_id", "   ");
        assertInvalid(JwtClaimValidators.clientId().validate(jwt));
    }

    @Test
    void rejectsMalformedClientId() {
        Jwt jwt = jwtWithClaim("client_id", "not a valid client id!");
        assertInvalid(JwtClaimValidators.clientId().validate(jwt));
    }

    @Test
    void acceptsWellFormedClientId() {
        Jwt jwt = jwtWithClaim("client_id", "CLIENT001");
        assertValid(JwtClaimValidators.clientId().validate(jwt));
    }

    // --- Authority mapping (ADR-007 §4) ---

    @Test
    void mapsScopeAsSpaceDelimitedStringToVerbatimAuthorities() {
        Jwt jwt = jwtWithClaim("scope", "notifications:read notifications:replay");
        assertThat(authoritiesOf(jwt))
                .containsExactlyInAnyOrder("notifications:read", "notifications:replay");
    }

    @Test
    void mapsScopeAsArrayToVerbatimAuthorities() {
        Jwt jwt = jwtWithClaim("scope", List.of("notifications:read", "notifications:replay"));
        assertThat(authoritiesOf(jwt))
                .containsExactlyInAnyOrder("notifications:read", "notifications:replay");
    }

    @Test
    void neverPrefixesAnAuthorityWithScope() {
        Jwt jwt = jwtWithClaim("scope", "notifications:read");
        assertThat(authoritiesOf(jwt)).noneMatch(authority -> authority.startsWith("SCOPE_"));
    }

    private static List<String> authoritiesOf(Jwt jwt) {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthorityPrefix("");
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static OAuth2TokenValidator<Jwt> fullValidator() {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(SKEW),
                new JwtIssuerValidator(ISSUER),
                JwtClaimValidators.audience(AUDIENCE),
                JwtClaimValidators.issuedAtNotInFuture(SKEW),
                JwtClaimValidators.maxLifetime(MAX_LIFETIME),
                JwtClaimValidators.subject(),
                JwtClaimValidators.clientId()));
    }

    private static void assertValid(OAuth2TokenValidatorResult result) {
        assertThat(result.hasErrors()).isFalse();
    }

    private static void assertInvalid(OAuth2TokenValidatorResult result) {
        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt.Builder validJwtBuilder() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(60))
                .subject("user-1")
                .claim("client_id", "CLIENT001")
                .claim("scope", "notifications:read");
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return jwt(Map.of(name, value));
    }

    private static Jwt jwtWithoutClaim(String omitted) {
        Map<String, Object> claims = defaultClaims();
        claims.remove(omitted);
        return buildJwt(claims);
    }

    private static Jwt jwt(Map<String, Object> overrides) {
        Map<String, Object> claims = defaultClaims();
        claims.putAll(overrides);
        return buildJwt(claims);
    }

    private static Jwt buildJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .build();
    }

    private static Map<String, Object> defaultClaims() {
        Instant now = Instant.now();
        return new HashMap<>(Map.of(
                "iss", ISSUER,
                "aud", List.of(AUDIENCE),
                "iat", now.minusSeconds(60),
                "exp", now.plusSeconds(60),
                "sub", "user-1",
                "client_id", "CLIENT001"));
    }
}
