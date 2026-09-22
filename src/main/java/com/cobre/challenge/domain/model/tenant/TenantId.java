package com.cobre.challenge.domain.model.tenant;

import java.util.regex.Pattern;

/** Identifies the tenant (client_id claim) a request or row belongs to. Never a plain String. */
public record TenantId(String value) {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public TenantId {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("TenantId must be 1-64 characters of [A-Za-z0-9_-]");
        }
    }
}
