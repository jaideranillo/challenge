package com.cobre.challenge.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthenticatedTenantResolverTest {

    private final AuthenticatedTenantResolver resolver = new AuthenticatedTenantResolver();

    @Test
    void resolvesTenantFromClientIdClaim() {
        Authentication authentication = jwtAuthenticationWithClaims(Map.of("client_id", "acme-corp"));

        TenantId tenantId = resolver.resolve(authentication);

        assertThat(tenantId).isEqualTo(new TenantId("acme-corp"));
    }

    @Test
    void throwsWhenNoAuthentication() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void throwsWhenAuthenticationIsNotJwt() {
        Authentication authentication = new TestingAuthenticationToken("user", "credentials");

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void throwsWhenClientIdClaimMissing() {
        Authentication authentication = jwtAuthenticationWithClaims(Map.of("sub", "someone"));

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void throwsWhenClientIdClaimBlank() {
        Authentication authentication = jwtAuthenticationWithClaims(Map.of("client_id", "   "));

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void throwsWhenClientIdClaimMalformed() {
        Authentication authentication = jwtAuthenticationWithClaims(Map.of("client_id", "not valid!"));

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JwtAuthenticationToken jwtAuthenticationWithClaims(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .claims(map -> map.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
