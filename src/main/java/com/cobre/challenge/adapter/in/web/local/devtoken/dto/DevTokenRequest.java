package com.cobre.challenge.adapter.in.web.local.devtoken.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Optional;

/**
 * {@code POST /local/dev-token} request body: which tenant and scopes to mint a local test JWT
 * for. {@code clientId} plays the role a real IdP would decide after authenticating the caller
 * (ADR-007 §7) — here you set it directly because you are standing in for the IdP in demo mode.
 */
public record DevTokenRequest(
        @NotBlank String clientId,
        String scope,
        Optional<String> subject,
        Optional<Long> lifetimeSeconds) {

    public DevTokenRequest {
        if (scope == null || scope.isBlank()) {
            scope = "notifications:read notifications:replay";
        }
        if (subject == null) {
            subject = Optional.empty();
        }
        if (lifetimeSeconds == null) {
            lifetimeSeconds = Optional.empty();
        }
    }
}
