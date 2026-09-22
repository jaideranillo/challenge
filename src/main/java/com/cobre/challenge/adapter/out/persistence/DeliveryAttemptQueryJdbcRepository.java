package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.DeliveryAttemptRowMapper;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptQueryRepositoryPort;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link DeliveryAttemptQueryRepositoryPort} on the API pool
 * (ADR-007 §5.4). Binds {@link TenantSessionBinder} before every query so RLS applies.
 *
 * <p>{@code delivery_attempts} has no {@code client_id} column, so the tenant predicate is
 * expressed as an {@code EXISTS} against the parent {@code deliveries} row — the same shape as
 * the RLS policy (TASK-008-08). This is still a bound query predicate, not a post-hoc Java
 * filter: a foreign delivery id matches zero rows in SQL, never filtered out afterward.
 *
 * <p>Ordered by {@code attempt_number}, served by {@code idx_delivery_attempts_delivery_attempt}.
 *
 * <p>No {@code @Transactional}. No mutable state. Safe to share across virtual threads.
 */
@Repository
public class DeliveryAttemptQueryJdbcRepository implements DeliveryAttemptQueryRepositoryPort {

    private static final String SELECT_SQL =
            "SELECT a.delivery_id, a.attempt_number, a.http_status, a.response_time_ms,"
                    + " a.response_excerpt, a.error, a.attempted_at"
                    + " FROM delivery_attempts a"
                    + " WHERE a.delivery_id = :delivery_id"
                    + " AND EXISTS ("
                    + " SELECT 1 FROM deliveries d"
                    + " WHERE d.delivery_id = a.delivery_id AND d.client_id = :client_id)"
                    + " ORDER BY a.attempt_number";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantSessionBinder tenantSessionBinder;
    private final DeliveryAttemptRowMapper rowMapper;

    public DeliveryAttemptQueryJdbcRepository(
            @Qualifier("apiJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            TenantSessionBinder tenantSessionBinder,
            DeliveryAttemptRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantSessionBinder = tenantSessionBinder;
        this.rowMapper = rowMapper;
    }

    @Override
    public List<DeliveryAttempt> findByDeliveryId(UUID deliveryId, TenantId tenant) {
        tenantSessionBinder.bind(tenant);
        return jdbcTemplate.query(SELECT_SQL,
                new MapSqlParameterSource()
                        .addValue("delivery_id", deliveryId)
                        .addValue("client_id", tenant.value()),
                rowMapper);
    }
}
