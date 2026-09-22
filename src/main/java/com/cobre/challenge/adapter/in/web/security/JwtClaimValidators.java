package com.cobre.challenge.adapter.in.web.security;

import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Claim validators for ADR-007 §3's token contract, beyond what Spring's built-in
 * {@code JwtIssuerValidator}/{@code JwtTimestampValidator} already cover. Each is a stateless
 * {@link OAuth2TokenValidator} composed by {@code JwtDecoderConfig} into the decoder's validator
 * chain — no validator here holds state or makes a network call (ADR-007 §3, "what deliberately
 * is not done").
 *
 * <p>None of these log a claim value: a failure result carries a generic {@link OAuth2Error}
 * with no claim content, per A09 and TASK-008-23's identical-401-body rule.
 */
public final class JwtClaimValidators {

    private static final String CLIENT_ID_CLAIM = "client_id";
    private static final OAuth2Error INVALID_TOKEN =
            new OAuth2Error("invalid_token", "The token contains invalid claims", null);

    private JwtClaimValidators() {
    }

    /** ADR-007 §3: {@code aud} must contain this service's configured audience. */
    public static OAuth2TokenValidator<Jwt> audience(String requiredAudience) {
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            if (audiences != null && audiences.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
        };
    }

    /** ADR-007 §3: {@code iat} must be present and not in the future beyond the clock skew. */
    public static OAuth2TokenValidator<Jwt> issuedAtNotInFuture(Duration clockSkew) {
        return jwt -> {
            Instant issuedAt = jwt.getIssuedAt();
            if (issuedAt == null || issuedAt.isAfter(Instant.now().plus(clockSkew))) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** ADR-007 §3: {@code exp - iat} must not exceed the configured ceiling. */
    public static OAuth2TokenValidator<Jwt> maxLifetime(Duration ceiling) {
        return jwt -> {
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null || Duration.between(issuedAt, expiresAt).compareTo(ceiling) > 0) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** ADR-007 §3: {@code sub} is required (logged for audit elsewhere; never used for authorization). */
    public static OAuth2TokenValidator<Jwt> subject() {
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /**
     * ADR-007 §3: {@code client_id} is required, non-blank, and must match {@link TenantId}'s
     * format exactly. Validated at the decoder, before any handler runs, so no endpoint can ever
     * see a principal without a tenant.
     *
     * <p>Constructs a {@link TenantId} to check the format rather than duplicating its regex:
     * this is the one rule, referenced once. {@code TenantBoundaryArchTest}'s "only
     * {@code AuthenticatedTenantResolver} constructs {@code TenantId}" rule is scoped to the
     * {@code adapter.in.web.security} package, not to that one class, and this validator lives
     * in that same package — so this stays compliant, and the constructed instance is discarded
     * immediately; {@link AuthenticatedTenantResolver} remains the only place a {@link TenantId}
     * is kept and used.
     */
    public static OAuth2TokenValidator<Jwt> clientId() {
        return jwt -> {
            String clientId = jwt.getClaimAsString(CLIENT_ID_CLAIM);
            if (clientId == null || clientId.isBlank()) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }
            try {
                new TenantId(clientId);
            } catch (IllegalArgumentException malformedClientId) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }
}
