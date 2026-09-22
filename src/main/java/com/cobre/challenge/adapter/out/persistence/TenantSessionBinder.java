package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.domain.model.tenant.TenantId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.client_id} on the API pool for the current transaction (ADR-007 §5.3)
 * - a plain collaborator called explicitly by the tenant-scoped adapters, deliberately
 * not AOP, so the binding is visible in the call path and cannot be silently disabled by
 * a forgotten annotation.
 */
@Component
public class TenantSessionBinder {

    // set_config(..., true): the third argument makes it transaction-local (equivalent
    // to SET LOCAL), and, unlike SET LOCAL, takes the value as a bound parameter rather
    // than a literal - interpolating a tenant into a session-configuration statement is
    // exactly the A05 path this form avoids.
    private static final String BIND_SQL = "SELECT set_config('app.client_id', :clientId, true)";

    private final NamedParameterJdbcTemplate apiJdbcTemplate;

    public TenantSessionBinder(@Qualifier("apiJdbcTemplate") NamedParameterJdbcTemplate apiJdbcTemplate) {
        this.apiJdbcTemplate = apiJdbcTemplate;
    }

    /**
     * Binds {@code tenant} to the current transaction on the API pool. No fallback, no
     * default, no throw-on-missing-transaction: an unbound tenant is not a security hole
     * because the RLS policies (V6) fail closed on a NULL session variable.
     */
    public void bind(TenantId tenant) {
        apiJdbcTemplate.queryForObject(BIND_SQL, new MapSqlParameterSource("clientId", tenant.value()), String.class);
    }
}
