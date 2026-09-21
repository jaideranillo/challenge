package com.cobre.challenge.adapter.out.persistence.mapper;

import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code delivery_attempts} result row to the {@link DeliveryAttempt} domain record.
 *
 * <p>Type-mapping conventions:
 * <ul>
 *   <li>{@code timestamptz}: {@code getObject(col, OffsetDateTime.class).toInstant()}.
 *   <li>Nullable {@code int} ({@code http_status}): {@code getObject} returning {@code Integer}
 *       (null for SQL NULL), then wrapped in {@link OptionalInt}.
 *   <li>{@code response_time_ms}: primitive {@code getInt} — returns 0 for SQL NULL, matching
 *       the {@code int} field in the domain record. A connection failure may leave this null;
 *       0 is the appropriate sentinel given the domain model has no {@code OptionalInt} here.
 *   <li>{@code uuid}: {@code getObject(col, UUID.class)}.
 * </ul>
 *
 * <p>The {@code id} identity column is never mapped — it is not a component of
 * {@link DeliveryAttempt} (ADR-003 §3: "not a public identifier").
 *
 * <p>This mapper is stateless and holds no mutable fields. Safe to share across virtual threads.
 *
 * <p><strong>A09:</strong> {@code response_excerpt} is the PII layer (ADR-002 §3.1) and is
 * never logged; no mapped value must reach a log or exception message from this class.
 */
@Component
public class DeliveryAttemptRowMapper implements RowMapper<DeliveryAttempt> {

    @Override
    public DeliveryAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer httpStatusVal = (Integer) rs.getObject("http_status");
        return new DeliveryAttempt(
                rs.getObject("delivery_id", UUID.class),
                rs.getInt("attempt_number"),
                httpStatusVal == null ? OptionalInt.empty() : OptionalInt.of(httpStatusVal),
                rs.getInt("response_time_ms"),
                Optional.ofNullable(rs.getString("response_excerpt")),
                Optional.ofNullable(rs.getString("error")),
                rs.getObject("attempted_at", OffsetDateTime.class).toInstant());
    }
}
