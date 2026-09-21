package com.cobre.challenge.adapter.out.persistence.mapper;

import com.cobre.challenge.domain.model.subscription.Subscription;
import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code subscriptions} result row to the {@link Subscription} domain record.
 *
 * <p>Type-mapping conventions:
 * <ul>
 *   <li>{@code timestamptz}: {@code getObject(col, OffsetDateTime.class).toInstant()}.
 *       Never {@code getTimestamp}.
 *   <li>Nullable {@code timestamptz}: {@code Optional.ofNullable} around the above.
 *   <li>Native Postgres enums ({@code circuit_state}, {@code verification_state}):
 *       {@code getString} then {@code Enum.valueOf}.
 *   <li>{@code text[]} ({@code event_types}): {@code rs.getArray(col).getArray()} cast to
 *       {@code String[]}, then {@code Set.of(...)}.
 *   <li>{@code uuid}: {@code getObject(col, UUID.class)}.
 * </ul>
 *
 * <p>Columns not in the {@link Subscription} domain record ({@code circuit_opened_at},
 * {@code circuit_backoff}, {@code consecutive_opens}, {@code verified_at}) are not mapped
 * here — they are used only in UPDATE WHERE clauses by the circuit-ops adapter
 * (TASK-004-19) and are never materialized into a domain object.
 *
 * <p>Note: TASK-004-05 documents the {@code interval} ({@code circuit_backoff}) mapping
 * convention as: read via {@link org.postgresql.util.PGInterval} or as {@code String},
 * convert to {@link java.time.Duration}. Since {@link Subscription} does not carry
 * {@code circuitBackoff} as a field, this mapper does not implement that conversion.
 * Logged in {@code docs/concerns.md}.
 *
 * <p>This mapper is stateless and holds no mutable fields. Safe to share across virtual threads.
 *
 * <p><strong>A09:</strong> {@code secret_ref} and {@code target_url} are never logged from here.
 */
@Component
public class SubscriptionRowMapper implements RowMapper<Subscription> {

    @Override
    public Subscription mapRow(ResultSet rs, int rowNum) throws SQLException {
        Array eventTypesArray = rs.getArray("event_types");
        Set<String> eventTypes = Set.of((String[]) eventTypesArray.getArray());

        return new Subscription(
                rs.getObject("subscription_id", UUID.class),
                rs.getString("client_id"),
                rs.getString("target_url"),
                rs.getString("secret_ref"),
                Optional.ofNullable(rs.getString("previous_secret_ref")),
                optionalInstant(rs, "previous_secret_expires_at"),
                eventTypes,
                rs.getBoolean("active"),
                VerificationState.valueOf(rs.getString("verification_state")),
                rs.getInt("max_concurrency"),
                CircuitState.valueOf(rs.getString("circuit_state")),
                optionalInstant(rs, "throttled_until"));
    }

    private static Optional<Instant> optionalInstant(ResultSet rs, String col) throws SQLException {
        return Optional.ofNullable(rs.getObject(col, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }
}
