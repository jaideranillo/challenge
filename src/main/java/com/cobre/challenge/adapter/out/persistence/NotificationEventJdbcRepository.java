package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.NotificationEventRowMapper;
import com.cobre.challenge.application.port.out.persistence.NotificationEventRepositoryPort;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link NotificationEventRepositoryPort}.
 *
 * <p>No {@code @Transactional}: transaction boundaries are the use case's responsibility
 * (CLAUDE.md, ADR-005 §1). Each method is a single, atomic SQL statement.
 *
 * <p>No {@code synchronized} and no {@code ThreadLocal} — this bean is stateless and safe to
 * share across virtual threads.
 */
@Repository
public class NotificationEventJdbcRepository implements NotificationEventRepositoryPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final NotificationEventRowMapper rowMapper;

    public NotificationEventJdbcRepository(
            NamedParameterJdbcTemplate jdbcTemplate, NotificationEventRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>ADR-002 §1.1 step 3: {@code ON CONFLICT (event_id) DO NOTHING}, using the primary key
     * on {@code notification_events.event_id} as the conflict target — no explicit inference
     * clause needed since the PK is not partial. ADR-003 Amendment A5 makes this port the only
     * writer of the table.
     *
     * <p>{@code DO NOTHING}, never {@code DO UPDATE}: {@code notification_events} is append-only
     * and immutable after insert ({@code V1}'s table comment: "Never updated. No updated_at by
     * design"). A second ingest carrying different {@code content} must not overwrite the first
     * — the stored row wins, which is what makes it an audit record.
     *
     * <p>{@code created_at} is bound from {@link NotificationEvent#createdAt()}, never from
     * {@code now()} and never a column default ({@code V1}'s column comment: a default "would
     * silently mask a producer omission and corrupt the API date-range filter").
     *
     * <p>Zero rows affected means the event was already stored — returned as {@code false},
     * never thrown, never logged at error level (ADR-002 §2.2's convention for a normal
     * conflict outcome, applied here per ADR-003 Amendment A5).
     */
    @Override
    public boolean insertIfAbsent(NotificationEvent event) {
        String sql = "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                + "VALUES (:event_id, :client_id, :event_type, :content, :created_at) "
                + "ON CONFLICT (event_id) DO NOTHING";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("event_id", event.eventId())
                .addValue("client_id", event.clientId())
                .addValue("event_type", event.eventType())
                .addValue("content", event.content())
                .addValue("created_at", OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));

        int affectedRows = jdbcTemplate.update(sql, params);
        return affectedRows == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Plain primary-key {@code SELECT}. Returns {@code Optional.empty()} rather than letting
     * {@link org.springframework.dao.EmptyResultDataAccessException} escape on no row.
     */
    @Override
    public Optional<NotificationEvent> findById(String eventId) {
        String sql = "SELECT event_id, client_id, event_type, content, created_at "
                + "FROM notification_events WHERE event_id = :event_id";

        List<NotificationEvent> results = jdbcTemplate.query(
                sql, new MapSqlParameterSource("event_id", eventId), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
