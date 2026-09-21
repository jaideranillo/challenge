package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.mapper.DeliveryAttemptRowMapper;
import com.cobre.challenge.application.port.out.persistence.DeliveryAttemptRepositoryPort;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link DeliveryAttemptRepositoryPort}.
 *
 * <p>This class is append-only: no {@code update} and no {@code delete} method exists or may be
 * added. ADR-003 §3 designates {@code delivery_attempts} as the audit trail a client can query
 * (ADR-005 §1); an update path would make it unable to serve that purpose.
 *
 * <p>Cross-tenant by design: {@code delivery_attempts} has no {@code client_id} column.
 * Tenant isolation for the client-facing path is enforced by resolving the parent delivery
 * through {@link com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort}
 * first, before accessing this port (ADR-007 §5.5's use-case ordering).
 *
 * <p>No {@code @Transactional}. Pairing this write with the outcome write on {@code deliveries}
 * in one transaction is the use case's responsibility. No mutable state.
 */
@Repository
public class DeliveryAttemptJdbcRepository implements DeliveryAttemptRepositoryPort {

    /** {@code response_excerpt} column width (varchar(1000) in V3). */
    private static final int RESPONSE_EXCERPT_MAX = 1000;

    private static final String SELECT_COLUMNS =
            "delivery_id, attempt_number, http_status, response_time_ms,"
                    + " response_excerpt, error, attempted_at";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DeliveryAttemptRowMapper rowMapper;

    public DeliveryAttemptJdbcRepository(
            NamedParameterJdbcTemplate jdbcTemplate, DeliveryAttemptRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
    }

    /**
     * Inserts one attempt row and returns the persisted record.
     *
     * <p>{@code id} is {@code GENERATED ALWAYS AS IDENTITY} and is never bound; the RETURNING
     * clause omits it since it is not a component of {@link DeliveryAttempt}.
     *
     * <p>{@code response_excerpt} is truncated to {@value #RESPONSE_EXCERPT_MAX} characters
     * before binding. A client returning a large error page must not fail the insert — that
     * would turn a failed delivery into a failed transaction and lose the outcome write
     * paired with it (A10). The truncated content is not logged (A09: PII layer, ADR-002 §3.1).
     */
    @Override
    public DeliveryAttempt insert(DeliveryAttempt attempt) {
        String sql =
                "INSERT INTO delivery_attempts"
                        + " (delivery_id, attempt_number, http_status, response_time_ms,"
                        + "  response_excerpt, error, attempted_at)"
                        + " VALUES"
                        + " (:delivery_id, :attempt_number, :http_status, :response_time_ms,"
                        + "  :response_excerpt, :error, :attempted_at)"
                        + " RETURNING " + SELECT_COLUMNS;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("delivery_id", attempt.deliveryId())
                .addValue("attempt_number", attempt.attemptNumber())
                .addValue("http_status", attempt.httpStatus().isPresent()
                        ? attempt.httpStatus().getAsInt() : null)
                .addValue("response_time_ms", attempt.responseTimeMs())
                .addValue("response_excerpt", truncate(attempt.responseExcerpt().orElse(null)))
                .addValue("error", attempt.error().orElse(null))
                .addValue("attempted_at",
                        OffsetDateTime.ofInstant(attempt.attemptedAt(), ZoneOffset.UTC));

        return jdbcTemplate.queryForObject(sql, params, rowMapper);
    }

    /**
     * Returns all attempts for a delivery in ascending {@code attempted_at} order.
     * Returns an empty list (never null) when no attempts exist (Effective Java Item 54).
     *
     * <p>Ordered chronologically because this feeds the "you never called me at 14:02"
     * answer (ADR-005 §1). No {@code client_id} predicate — see class javadoc.
     */
    @Override
    public List<DeliveryAttempt> findByDeliveryId(UUID deliveryId) {
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM delivery_attempts"
                + " WHERE delivery_id = :delivery_id"
                + " ORDER BY attempted_at ASC";
        List<DeliveryAttempt> result = jdbcTemplate.query(
                sql, new MapSqlParameterSource("delivery_id", deliveryId), rowMapper);
        return result == null ? List.of() : result;
    }

    /** Truncates to column width; returns null unchanged. */
    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= RESPONSE_EXCERPT_MAX ? value : value.substring(0, RESPONSE_EXCERPT_MAX);
    }
}
