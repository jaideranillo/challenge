package com.cobre.challenge.adapter.out.persistence.mapper;

import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code notification_events} result row to the {@link NotificationEvent} domain record.
 *
 * <p>{@code created_at} follows this package's convention for {@code timestamptz}:
 * {@code getObject(col, OffsetDateTime.class).toInstant()}, never {@code getTimestamp} (which
 * silently applies the JVM default zone).
 *
 * <p>This mapper is stateless and holds no mutable fields; safe to share across virtual threads.
 *
 * <p><strong>A09:</strong> never logs {@code content} — the PII layer (ADR-002 §3.1).
 */
@Component
public class NotificationEventRowMapper implements RowMapper<NotificationEvent> {

    @Override
    public NotificationEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationEvent(
                rs.getString("event_id"),
                rs.getString("client_id"),
                rs.getString("event_type"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
