package com.cobre.challenge.adapter.in.web.local.devtoken.dto;

import java.time.Instant;

/** The minted token, plus its claims echoed back so it's obvious which tenant it's for. */
public record DevTokenResponse(String token, String clientId, String scope, Instant expiresAt) {}
