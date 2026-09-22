package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.NotificationEventRowMapper;
import com.cobre.challenge.application.port.out.persistence.NotificationEventQueryRepositoryPort;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link NotificationEventQueryRepositoryPort} on the API pool
 * (ADR-007 §5.4). Binds {@link TenantSessionBinder} before every query so RLS applies, and binds
 * {@code client_id} as a query predicate (ADR-007 §I-B/I-D, A01/IDOR).
 *
 * <p>{@code event_id} is bound as a {@code String} against the {@code text} column (V1 migration).
 *
 * <p>No {@code @Transactional}. No mutable state. Safe to share across virtual threads.
 */
@Repository
public class NotificationEventQueryJdbcRepository implements NotificationEventQueryRepositoryPort {

    private static final String SELECT_COLUMNS = "event_id, client_id, event_type, content, created_at";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantSessionBinder tenantSessionBinder;
    private final NotificationEventRowMapper rowMapper;

    public NotificationEventQueryJdbcRepository(
            @Qualifier("apiJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            TenantSessionBinder tenantSessionBinder,
            NotificationEventRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantSessionBinder = tenantSessionBinder;
        this.rowMapper = rowMapper;
    }

    @Override
    public Optional<NotificationEvent> findById(String eventId, TenantId tenant) {
        tenantSessionBinder.bind(tenant);
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM notification_events"
                + " WHERE event_id = :event_id AND client_id = :client_id";

        List<NotificationEvent> results = jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("event_id", eventId)
                        .addValue("client_id", tenant.value()),
                rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
