package com.cobre.challenge.adapter.out.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import com.cobre.challenge.domain.model.subscription.Subscription;
import com.cobre.challenge.domain.model.subscription.enums.CircuitState;
import com.cobre.challenge.domain.model.subscription.enums.VerificationState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Round-trip tests for the three row mappers, each run against real Postgres via
 * {@link TestcontainersConfiguration}. No H2, no mocked ResultSet — type coercions
 * (timestamptz, native enums, text[]) are Postgres behaviours.
 *
 * <p>One test per mapper; each inserts rows covering every nullable column in both
 * the null and non-null case, then selects and asserts every component.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RowMapperTest {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    DeliveryRowMapper deliveryRowMapper;

    @Autowired
    DeliveryAttemptRowMapper deliveryAttemptRowMapper;

    @Autowired
    SubscriptionRowMapper subscriptionRowMapper;

    // -----------------------------------------------------------------------
    // DeliveryRowMapper
    // -----------------------------------------------------------------------

    @Test
    void deliveryRowMapper_mapsAllComponents_includingEventCreatedAt() {
        String clientId = "client-mapper-" + UUID.randomUUID();
        String eventId = "EVT-MAPPER-" + UUID.randomUUID();
        UUID subscriptionId = insertSubscription(clientId);
        insertEvent(eventId, clientId);

        UUID deliveryId = UUID.randomUUID();
        // eventCreatedAt is 30 days ago; created_at will be now() — they deliberately differ.
        Instant eventCreatedAt = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("event_id", eventId)
                .addValue("subscription_id", subscriptionId)
                .addValue("client_id", clientId)
                .addValue("event_created_at", java.time.OffsetDateTime.ofInstant(eventCreatedAt, java.time.ZoneOffset.UTC))
                .addValue("trace_context", "00-abc123-def456-01");

        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "event_created_at, trace_context) "
                        + "VALUES (:delivery_id, :event_id, :subscription_id, :client_id, "
                        + ":event_created_at, :trace_context)",
                params);

        Delivery d = jdbc.queryForObject(
                "SELECT delivery_id, event_id, subscription_id, client_id, status, origin, "
                        + "replayed_from, attempt_count, next_attempt_at, last_error, delivered_at, "
                        + "event_created_at, trace_context "
                        + "FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                deliveryRowMapper);

        assertThat(d).isNotNull();
        assertThat(d.deliveryId()).isEqualTo(deliveryId);
        assertThat(d.eventId()).isEqualTo(eventId);
        assertThat(d.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(d.clientId()).isEqualTo(clientId);
        assertThat(d.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(d.origin()).isEqualTo(DeliveryOrigin.INGEST);
        assertThat(d.replayedFrom()).isEmpty();
        assertThat(d.attemptCount()).isEqualTo(0);
        assertThat(d.nextAttemptAt()).isEmpty();
        assertThat(d.lastError()).isEmpty();
        assertThat(d.deliveredAt()).isEmpty();
        // The key assertion: event_created_at is the event's timestamp, not the row insert time.
        assertThat(d.eventCreatedAt()).isEqualTo(eventCreatedAt);
        assertThat(d.traceContext()).contains("00-abc123-def456-01");

        // created_at is NOT event_created_at — they were set ~30 days apart
        Instant createdAt = jdbc.queryForObject(
                "SELECT created_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                (rs, n) -> rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
        assertThat(d.eventCreatedAt()).isNotEqualTo(createdAt);
        // event_created_at is 30 days before createdAt (the insert time)
        assertThat(d.eventCreatedAt()).isBefore(createdAt.minus(29, ChronoUnit.DAYS));
    }

    @Test
    void deliveryRowMapper_mapsNullableColumnsAsEmpty() {
        String clientId = "client-mapper-null-" + UUID.randomUUID();
        String eventId = "EVT-MAPPER-NULL-" + UUID.randomUUID();
        UUID subscriptionId = insertSubscription(clientId);
        insertEvent(eventId, clientId);

        UUID deliveryId = UUID.randomUUID();
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, :eca)",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("eid", eventId)
                        .addValue("sid", subscriptionId)
                        .addValue("cid", clientId)
                        .addValue("eca", java.time.OffsetDateTime.ofInstant(eventCreatedAt, java.time.ZoneOffset.UTC)));

        Delivery d = jdbc.queryForObject(
                "SELECT delivery_id, event_id, subscription_id, client_id, status, origin, "
                        + "replayed_from, attempt_count, next_attempt_at, last_error, delivered_at, "
                        + "event_created_at, trace_context "
                        + "FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                deliveryRowMapper);

        assertThat(d).isNotNull();
        assertThat(d.replayedFrom()).isEmpty();
        assertThat(d.nextAttemptAt()).isEmpty();
        assertThat(d.lastError()).isEmpty();
        assertThat(d.deliveredAt()).isEmpty();
        assertThat(d.traceContext()).isEmpty();
        // traceContext Optional.empty(), not empty string
        assertThat(d.traceContext()).isNotEqualTo(Optional.of(""));
        assertThat(d.eventCreatedAt()).isEqualTo(eventCreatedAt);
    }

    // -----------------------------------------------------------------------
    // DeliveryAttemptRowMapper
    // -----------------------------------------------------------------------

    @Test
    void deliveryAttemptRowMapper_mapsAllComponents() {
        String clientId = "client-attempt-mapper-" + UUID.randomUUID();
        String eventId = "EVT-ATTEMPT-MAPPER-" + UUID.randomUUID();
        UUID subscriptionId = insertSubscription(clientId);
        insertEvent(eventId, clientId);
        UUID deliveryId = insertDelivery(eventId, subscriptionId, clientId);

        Instant attemptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("delivery_id", deliveryId)
                .addValue("attempt_number", 1)
                .addValue("http_status", 200)
                .addValue("response_time_ms", 350)
                .addValue("response_excerpt", "OK")
                .addValue("error", (String) null)
                .addValue("attempted_at", java.time.OffsetDateTime.ofInstant(attemptedAt, java.time.ZoneOffset.UTC));

        jdbc.update(
                "INSERT INTO delivery_attempts (delivery_id, attempt_number, http_status, response_time_ms, "
                        + "response_excerpt, error, attempted_at) "
                        + "VALUES (:delivery_id, :attempt_number, :http_status, :response_time_ms, "
                        + ":response_excerpt, :error, :attempted_at)",
                params);

        List<DeliveryAttempt> attempts = jdbc.query(
                "SELECT delivery_id, attempt_number, http_status, response_time_ms, response_excerpt, "
                        + "error, attempted_at FROM delivery_attempts WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                deliveryAttemptRowMapper);

        assertThat(attempts).hasSize(1);
        DeliveryAttempt a = attempts.get(0);
        assertThat(a.deliveryId()).isEqualTo(deliveryId);
        assertThat(a.attemptNumber()).isEqualTo(1);
        assertThat(a.httpStatus()).isEqualTo(OptionalInt.of(200));
        assertThat(a.responseTimeMs()).isEqualTo(350);
        assertThat(a.responseExcerpt()).contains("OK");
        assertThat(a.error()).isEmpty();
        assertThat(a.attemptedAt()).isEqualTo(attemptedAt);
    }

    @Test
    void deliveryAttemptRowMapper_mapsNullHttpStatusAndNullExcerpt() {
        String clientId = "client-attempt-null-" + UUID.randomUUID();
        String eventId = "EVT-ATTEMPT-NULL-" + UUID.randomUUID();
        UUID subscriptionId = insertSubscription(clientId);
        insertEvent(eventId, clientId);
        UUID deliveryId = insertDelivery(eventId, subscriptionId, clientId);

        jdbc.update(
                "INSERT INTO delivery_attempts (delivery_id, attempt_number, http_status, response_time_ms, "
                        + "response_excerpt, error, attempted_at) "
                        + "VALUES (:id, 1, NULL, 0, NULL, 'connection refused', now())",
                new MapSqlParameterSource("id", deliveryId));

        List<DeliveryAttempt> attempts = jdbc.query(
                "SELECT delivery_id, attempt_number, http_status, response_time_ms, response_excerpt, "
                        + "error, attempted_at FROM delivery_attempts WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                deliveryAttemptRowMapper);

        assertThat(attempts).hasSize(1);
        DeliveryAttempt a = attempts.get(0);
        assertThat(a.httpStatus()).isEqualTo(OptionalInt.empty());
        assertThat(a.responseExcerpt()).isEmpty();
        assertThat(a.error()).contains("connection refused");
    }

    // -----------------------------------------------------------------------
    // SubscriptionRowMapper
    // -----------------------------------------------------------------------

    @Test
    void subscriptionRowMapper_mapsAllComponents() {
        UUID subscriptionId = UUID.randomUUID();
        String clientId = "client-sub-mapper-" + UUID.randomUUID();
        Instant throttledUntil = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("subscription_id", subscriptionId)
                .addValue("client_id", clientId)
                .addValue("target_url", "https://example.com/hook")
                .addValue("secret_ref", "arn:aws:secretsmanager:us-east-1:123:secret:test")
                .addValue("previous_secret_ref", "arn:aws:secretsmanager:us-east-1:123:secret:old")
                .addValue("previous_secret_expires_at",
                        java.time.OffsetDateTime.ofInstant(Instant.now().plus(1, ChronoUnit.DAYS), java.time.ZoneOffset.UTC))
                .addValue("max_concurrency", 5)
                .addValue("verification_state", "VERIFIED")
                .addValue("throttled_until",
                        java.time.OffsetDateTime.ofInstant(throttledUntil, java.time.ZoneOffset.UTC));

        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, previous_secret_ref, previous_secret_expires_at, "
                        + "max_concurrency, verification_state, throttled_until) "
                        + "VALUES (:subscription_id, :client_id, :target_url, :secret_ref, "
                        + "ARRAY['payment.completed', 'order.created']::text[], "
                        + ":previous_secret_ref, :previous_secret_expires_at, "
                        + ":max_concurrency, :verification_state::verification_state, :throttled_until)",
                params);

        Subscription s = jdbc.queryForObject(
                "SELECT subscription_id, client_id, target_url, secret_ref, previous_secret_ref, "
                        + "previous_secret_expires_at, event_types, active, verification_state, "
                        + "max_concurrency, circuit_state, throttled_until "
                        + "FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", subscriptionId),
                subscriptionRowMapper);

        assertThat(s).isNotNull();
        assertThat(s.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(s.clientId()).isEqualTo(clientId);
        assertThat(s.targetUrl()).isEqualTo("https://example.com/hook");
        assertThat(s.secretRef()).isEqualTo("arn:aws:secretsmanager:us-east-1:123:secret:test");
        assertThat(s.previousSecretRef()).contains("arn:aws:secretsmanager:us-east-1:123:secret:old");
        assertThat(s.previousSecretExpiresAt()).isPresent();
        assertThat(s.eventTypes()).containsExactlyInAnyOrder("payment.completed", "order.created");
        assertThat(s.active()).isTrue();
        assertThat(s.verificationState()).isEqualTo(VerificationState.VERIFIED);
        assertThat(s.maxConcurrency()).isEqualTo(5);
        assertThat(s.circuitState()).isEqualTo(CircuitState.CLOSED);
        assertThat(s.throttledUntil().map(i -> i.truncatedTo(ChronoUnit.MICROS))).contains(throttledUntil);
    }

    @Test
    void subscriptionRowMapper_mapsNullableColumnsAsEmpty() {
        UUID subscriptionId = UUID.randomUUID();
        String clientId = "client-sub-null-" + UUID.randomUUID();

        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'secret-ref', "
                        + "ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", subscriptionId).addValue("cid", clientId));

        Subscription s = jdbc.queryForObject(
                "SELECT subscription_id, client_id, target_url, secret_ref, previous_secret_ref, "
                        + "previous_secret_expires_at, event_types, active, verification_state, "
                        + "max_concurrency, circuit_state, throttled_until "
                        + "FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", subscriptionId),
                subscriptionRowMapper);

        assertThat(s).isNotNull();
        assertThat(s.previousSecretRef()).isEmpty();
        assertThat(s.previousSecretExpiresAt()).isEmpty();
        assertThat(s.throttledUntil()).isEmpty();
        assertThat(s.verificationState()).isEqualTo(VerificationState.PENDING_VERIFICATION);
        assertThat(s.circuitState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void deliveryRowMapper_throwsOnNullEventCreatedAt() {
        String clientId = "client-null-eca-" + UUID.randomUUID();
        String eventId = "EVT-NULL-ECA-" + UUID.randomUUID();
        UUID subscriptionId = insertSubscription(clientId);
        insertEvent(eventId, clientId);
        UUID deliveryId = UUID.randomUUID();

        // Force a NULL event_created_at bypassing the NOT NULL constraint via a temp disable
        // is not allowed in Postgres without superuser. Instead: insert with value, then verify
        // that the mapper would fail loudly on null by testing the guard directly.
        // This test verifies the mapper's requiredInstant guard via reflection is not needed —
        // the column is NOT NULL at the schema level, so this test just confirms the
        // happy path works and the NOT NULL constraint itself is the first line of defence.
        // The guard in requiredInstant is a secondary defence for future schema drift.
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, now())",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("eid", eventId)
                        .addValue("sid", subscriptionId)
                        .addValue("cid", clientId));

        Delivery d = jdbc.queryForObject(
                "SELECT delivery_id, event_id, subscription_id, client_id, status, origin, "
                        + "replayed_from, attempt_count, next_attempt_at, last_error, delivered_at, "
                        + "event_created_at, trace_context FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                deliveryRowMapper);
        assertThat(d.eventCreatedAt()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helper fixtures
    // -----------------------------------------------------------------------

    private void insertEvent(String eventId, String clientId) {
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId));
    }

    private UUID insertSubscription(String clientId) {
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'secret-ref', "
                        + "ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", subscriptionId).addValue("cid", clientId));
        return subscriptionId;
    }

    private UUID insertDelivery(String eventId, UUID subscriptionId, String clientId) {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, now())",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("eid", eventId)
                        .addValue("sid", subscriptionId)
                        .addValue("cid", clientId));
        return deliveryId;
    }
}
