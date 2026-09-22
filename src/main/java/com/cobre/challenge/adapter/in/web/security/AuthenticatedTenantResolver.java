package com.cobre.challenge.adapter.in.web.security;

import com.cobre.challenge.domain.model.tenant.TenantId;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the {@link TenantId} of the current request from the {@code client_id} claim of the
 * authenticated JWT. The only production construction site of {@link TenantId} (ADR-007 5.1).
 */
public final class AuthenticatedTenantResolver {

    private static final String CLIENT_ID_CLAIM = "client_id";

    public TenantId resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new InsufficientAuthenticationException("No authenticated JWT tenant");
        }

        Jwt jwt = jwtAuthentication.getToken();
        String clientId = jwt.getClaimAsString(CLIENT_ID_CLAIM);
        if (clientId == null || clientId.isBlank()) {
            throw new InsufficientAuthenticationException("Missing client_id claim");
        }

        return new TenantId(clientId);
    }
}
