package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.challenge.domain.model.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Plain JUnit, mocked template, no database (ADR-007 §5.3 / TASK-008-10 phase rule).
 * Proves the tenant is passed as a bound parameter, never interpolated, and that the
 * statement uses the transaction-local {@code set_config(..., true)} form. The
 * behavioral proof of what PostgreSQL does with the setting is deferred (Testcontainers).
 */
class TenantSessionBinderTest {

    private final NamedParameterJdbcTemplate apiJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final TenantSessionBinder binder = new TenantSessionBinder(apiJdbcTemplate);

    @Test
    void bindsTheTenantAsABoundParameterUsingTheTransactionLocalForm() {
        TenantId tenant = new TenantId("acme-corp");
        when(apiJdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn("acme-corp");

        binder.bind(tenant);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(apiJdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(String.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).doesNotContain("acme-corp");
        assertThat(sql).containsIgnoringCase("set_config");
        assertThat(sql).contains("true");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.hasValue("clientId")).isTrue();
        assertThat(params.getValue("clientId")).isEqualTo("acme-corp");
    }
}
