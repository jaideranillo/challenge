package com.cobre.challenge.adapter.out.persistence;

import com.cobre.challenge.adapter.out.persistence.cursor.DeliveryPageCursor;
import com.cobre.challenge.adapter.out.persistence.mapper.DeliveryRowMapper;
import com.cobre.challenge.application.port.out.persistence.DeliveryQueryRepositoryPort;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link DeliveryQueryRepositoryPort}.
 *
 * <p><strong>Every method on this class binds {@code client_id}.</strong> No unscoped overload
 * exists or will be added. The unscoped read that does exist lives on
 * {@link DeliveryPipelineRepositoryPort} and is unreachable from a client-facing use case
 * (ADR-007 Amendment E1). Tenant isolation on this class is structural: the predicate is part
 * of the SQL, never a post-hoc Java filter.
 *
 * <p>Runs on the API pool ({@code apiJdbcTemplate}, ADR-007 §5.4) so row level security applies;
 * {@link TenantSessionBinder#bind(TenantId)} is called before every query to set the session
 * variable the RLS policies read. The bound SQL predicate below is layer 2 (ADR-007 §I-B); the
 * session binding is layer 3 (§I-D) — neither replaces the other.
 *
 * <p>No {@code @Transactional}. No mutable state. Safe to share across virtual threads.
 */
@Repository
public class DeliveryQueryJdbcRepository implements DeliveryQueryRepositoryPort {

    private static final String SELECT_COLUMNS =
            "delivery_id, event_id, subscription_id, client_id, status, origin,"
                    + " replayed_from, attempt_count, next_attempt_at, last_error,"
                    + " delivered_at, event_created_at, trace_context";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantSessionBinder tenantSessionBinder;
    private final DeliveryRowMapper deliveryRowMapper;

    public DeliveryQueryJdbcRepository(
            @Qualifier("apiJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            TenantSessionBinder tenantSessionBinder,
            DeliveryRowMapper deliveryRowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantSessionBinder = tenantSessionBinder;
        this.deliveryRowMapper = deliveryRowMapper;
    }

    // -----------------------------------------------------------------------
    // TASK-004-13: tenant-scoped findById
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Both {@code delivery_id} and {@code client_id} are query predicates — not post-hoc
     * filters. This is ADR-007 §I-B's layer 2: the predicate is in the SQL, enforced at every
     * call site by construction.
     *
     * <p>A row belonging to another tenant returns {@link Optional#empty()}, indistinguishable
     * from a row that does not exist. ADR-005 §1 maps this to 404 rather than 403 so the endpoint
     * does not leak the existence of another tenant's ids. An adapter that threw a
     * distinguishable "wrong tenant" exception would hand the web layer the means to leak it.
     */
    @Override
    public Optional<Delivery> findById(UUID deliveryId, TenantId tenant) {
        tenantSessionBinder.bind(tenant);
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM deliveries"
                + " WHERE delivery_id = :delivery_id AND client_id = :client_id";

        List<Delivery> results = jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("delivery_id", deliveryId)
                        .addValue("client_id", tenant.value()),
                deliveryRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -----------------------------------------------------------------------
    // TASK-004-14: keyset-paginated findPage on event_created_at
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p><strong>All date predicates and the entire keyset run on {@code event_created_at},
     * never on {@code created_at}.</strong> For a {@code REPLAY} or {@code RECOVERED} row,
     * {@code created_at} is when the replay was requested; {@code event_created_at} is when the
     * platform event happened. A client asking for deliveries "within a date window" means the
     * latter: filtering on {@code created_at} would file a replayed delivery under the wrong day
     * and hide it from the window the client actually asked about (ADR-003 Amendment A4,
     * ADR-005 Amendment D2).
     *
     * <p>{@code client_id} is unconditional — present in every filter combination, never
     * optional. The static-fragment composition below enforces this structurally: the base SQL
     * includes {@code client_id} before any optional fragment is appended.
     *
     * <p>The keyset uses row-value comparison {@code (event_created_at, delivery_id) < (:ts, :id)}
     * so Postgres can match it against the composite index {@code idx_deliveries_client_event_created_at}.
     * The expanded-OR form ({@code event_created_at < :ts OR (event_created_at = :ts AND delivery_id < :id)})
     * frequently degrades to a filter.
     *
     * <p>{@code delivery_id} is the mandatory tiebreak: {@code event_created_at} is not unique
     * (a fan-out writes N rows with the same value), and without the tiebreak a page boundary
     * landing inside a fan-out loses or repeats rows.
     *
     * <p>Fetches {@code limit + 1} to compute {@code hasMore} without a second {@code COUNT}.
     *
     * <p>Optional predicates use static SQL fragments with named parameters. No user value is
     * ever concatenated into SQL text (A05).
     *
     * <p>A malformed cursor propagates {@link DeliveryPageCursor.MalformedCursorException} — it
     * must not silently return page 1 (A10).
     */
    @Override
    public DeliveryPage findPage(TenantId tenant, DeliveryPageQuery query, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        tenantSessionBinder.bind(tenant);

        Optional<Instant> eventCreatedFrom = query.eventCreatedFrom();
        Optional<Instant> eventCreatedTo = query.eventCreatedTo();
        Optional<DeliveryStatus> status = query.status();
        Optional<String> cursor = query.cursor();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("client_id", tenant.value())
                .addValue("limit_plus_one", limit + 1);

        StringBuilder sql = new StringBuilder(
                "SELECT " + SELECT_COLUMNS
                        + " FROM deliveries"
                        + " WHERE client_id = :client_id");

        // Optional predicates — each appends a static fragment and a named parameter.
        // client_id is unconditional above; none of the branches below can remove it.
        if (eventCreatedFrom.isPresent()) {
            sql.append(" AND event_created_at >= :event_created_from");
            params.addValue("event_created_from",
                    OffsetDateTime.ofInstant(eventCreatedFrom.get(), ZoneOffset.UTC));
        }
        if (eventCreatedTo.isPresent()) {
            sql.append(" AND event_created_at < :event_created_to");
            params.addValue("event_created_to",
                    OffsetDateTime.ofInstant(eventCreatedTo.get(), ZoneOffset.UTC));
        }
        if (status.isPresent()) {
            sql.append(" AND status = :status::delivery_status");
            params.addValue("status", status.get().name());
        }
        if (cursor.isPresent()) {
            // Decode throws MalformedCursorException on invalid input — propagates to the caller.
            // Decoded values are parsed to typed Instant/UUID before binding (A05).
            DeliveryPageCursor decoded = DeliveryPageCursor.decode(cursor.get());
            sql.append(" AND (event_created_at, delivery_id) < (:cursor_ts, :cursor_id)");
            params.addValue("cursor_ts", OffsetDateTime.ofInstant(decoded.eventCreatedAt(), ZoneOffset.UTC));
            params.addValue("cursor_id", decoded.deliveryId());
        }

        sql.append(" ORDER BY event_created_at DESC, delivery_id DESC");
        sql.append(" LIMIT :limit_plus_one");

        List<Delivery> fetched = jdbcTemplate.query(sql.toString(), params, deliveryRowMapper);

        boolean hasMore = fetched.size() > limit;
        List<Delivery> page = hasMore ? fetched.subList(0, limit) : fetched;

        Optional<String> nextCursor = hasMore
                ? Optional.of(buildCursor(page.get(page.size() - 1)))
                : Optional.empty();

        return new DeliveryPage(page, nextCursor);
    }

    private static String buildCursor(Delivery last) {
        return new DeliveryPageCursor(last.eventCreatedAt(), last.deliveryId()).encode();
    }
}
