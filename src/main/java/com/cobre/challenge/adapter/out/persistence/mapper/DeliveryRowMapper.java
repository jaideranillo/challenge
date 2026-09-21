package com.cobre.challenge.adapter.out.persistence.mapper;

import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code deliveries} result row to the {@link Delivery} domain record.
 *
 * <p>Type-mapping conventions:
 * <ul>
 *   <li>{@code timestamptz} columns: {@code getObject(col, OffsetDateTime.class).toInstant()}.
 *       Never {@code getTimestamp}, which silently applies the JVM default zone.
 *   <li>Nullable {@code timestamptz}: {@code Optional.ofNullable} around the above.
 *   <li>Native Postgres enums: {@code getString} then {@code Enum.valueOf}.
 *   <li>{@code uuid}: {@code getObject(col, UUID.class)}.
 * </ul>
 *
 * <p>{@code created_at} and {@code updated_at} are not mapped: they are persistence-only
 * audit columns that no use case reads, and they are not components of {@link Delivery}
 * (ADR-003 Amendment A3).
 *
 * <p>{@code event_created_at} is mapped as a <em>required</em> {@link Instant}. A null in
 * that column is a bug in the insert adapter (it should always be bound from
 * {@link Delivery#eventCreatedAt()}). Substituting {@code created_at} here would
 * silently reintroduce the replay-timestamp bug ADR-003 Amendment A4 exists to prevent.
 *
 * <p>This mapper is stateless and holds no mutable fields. It is safe to share across
 * virtual threads.
 *
 * <p><strong>A09:</strong> this class never logs any mapped value. {@code content} and
 * {@code response_excerpt} are the PII layer (ADR-002 §3.1); no delivery field must
 * reach a log or exception message from here.
 */
@Component
public class DeliveryRowMapper implements RowMapper<Delivery> {

    @Override
    public Delivery mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Delivery(
                rs.getObject("delivery_id", UUID.class),
                rs.getString("event_id"),
                rs.getObject("subscription_id", UUID.class),
                rs.getString("client_id"),
                DeliveryStatus.valueOf(rs.getString("status")),
                DeliveryOrigin.valueOf(rs.getString("origin")),
                Optional.ofNullable(rs.getObject("replayed_from", UUID.class)),
                rs.getInt("attempt_count"),
                optionalInstant(rs, "next_attempt_at"),
                Optional.ofNullable(rs.getString("last_error")),
                optionalInstant(rs, "delivered_at"),
                requiredInstant(rs, "event_created_at"),
                Optional.ofNullable(rs.getString("trace_context")));
    }

    /**
     * Reads a required {@code timestamptz} column as {@link Instant}.
     * Throws {@link IllegalStateException} if the column is null — a null here means
     * the insert adapter did not supply the value, which is a bug, not a case to
     * tolerate with a fallback.
     */
    private static Instant requiredInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        if (odt == null) {
            throw new IllegalStateException(
                    "Required column '" + col + "' was null — the insert adapter must always supply it");
        }
        return odt.toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet rs, String col) throws SQLException {
        return Optional.ofNullable(rs.getObject(col, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }
}
