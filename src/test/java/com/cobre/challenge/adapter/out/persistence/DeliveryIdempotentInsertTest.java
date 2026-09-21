package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DeliveryPipelineJdbcRepository#insertIfAbsent} and
 * {@link DeliveryPipelineJdbcRepository#findLiveByEventAndSubscription}: the
 * {@code idx_deliveries_live_pair} partial-index semantics the ingest use case depends on
 * (ADR-003 §2, Amendment A5).
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}, Flyway-migrated. No H2, no mocks.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@SpringBootTest
class DeliveryIdempotentInsertTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void perTestFixtures() {
        clientId = "client-idempotent-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    /** Case 1: first insertIfAbsent returns the inserted row, with the expected defaults. */
    @Test
    void insertIfAbsent_firstCall_returnsInsertedRow() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery d = buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.of("traceparent-1"));

        Optional<Delivery> result = repo.insertIfAbsent(d);

        assertThat(result).isPresent();
        Delivery persisted = result.get();
        assertThat(persisted.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(persisted.origin()).isEqualTo(DeliveryOrigin.INGEST);
        assertThat(persisted.attemptCount()).isEqualTo(0);
        assertThat(persisted.traceContext()).contains("traceparent-1");
        assertThat(persisted.eventCreatedAt()).isEqualTo(eventCreatedAt);
    }

    /**
     * Case 2: a second insertIfAbsent for the same live pair returns empty, throws nothing,
     * and leaves exactly one row for the pair.
     */
    @Test
    void insertIfAbsent_secondCallSamePair_returnsEmpty_noException_oneRow() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repo.insertIfAbsent(buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));

        Optional<Delivery> second = repo.insertIfAbsent(
                buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));

        assertThat(second).isEmpty();
        assertThatCode(() -> repo.insertIfAbsent(
                        buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty())))
                .doesNotThrowAnyException();
        assertThat(countRowsForPair(eventId, subscriptionId)).isEqualTo(1);
    }

    /** Case 3: findLiveByEventAndSubscription then returns that first row. */
    @Test
    void findLiveByEventAndSubscription_afterConflict_returnsFirstRow() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery first = repo.insertIfAbsent(
                        buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()))
                .orElseThrow();

        repo.insertIfAbsent(buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));

        Optional<Delivery> live = repo.findLiveByEventAndSubscription(eventId, subscriptionId);

        assertThat(live).isPresent();
        assertThat(live.get().deliveryId()).isEqualTo(first.deliveryId());
    }

    /**
     * Case 4: a terminal state frees the pair. Once the live row reaches DELIVERED or DEAD
     * (driven through the port), insertIfAbsent for the same pair inserts a new row, and
     * findLiveByEventAndSubscription returns the new one, not the terminal one.
     */
    @Test
    void insertIfAbsent_afterDelivered_freesThePair_insertsNewRow() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery first = repo.insertIfAbsent(
                        buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()))
                .orElseThrow();

        // Drive to a terminal state through the port: QUEUED -> PROCESSING -> DELIVERED.
        setStatusRaw(first.deliveryId(), "QUEUED");
        assertThat(repo.claimForProcessing(first.deliveryId(), Instant.now())).isTrue();
        assertThat(repo.markDelivered(first.deliveryId(), Instant.now())).isTrue();

        Optional<Delivery> second = repo.insertIfAbsent(
                buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));

        assertThat(second).isPresent();
        assertThat(second.get().deliveryId()).isNotEqualTo(first.deliveryId());
        assertThat(repo.findLiveByEventAndSubscription(eventId, subscriptionId))
                .map(Delivery::deliveryId)
                .contains(second.get().deliveryId());
    }

    /** Case 4 (DEAD variant): the same freeing behavior for the DEAD terminal state. */
    @Test
    void insertIfAbsent_afterDead_freesThePair_insertsNewRow() {
        String eventId = insertEvent(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery first = repo.insertIfAbsent(
                        buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()))
                .orElseThrow();

        setStatusRaw(first.deliveryId(), "QUEUED");
        assertThat(repo.claimForProcessing(first.deliveryId(), Instant.now())).isTrue();
        assertThat(repo.markDead(first.deliveryId(), "boom", Instant.now())).isTrue();

        Optional<Delivery> second = repo.insertIfAbsent(
                buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));

        assertThat(second).isPresent();
        assertThat(second.get().deliveryId()).isNotEqualTo(first.deliveryId());
        assertThat(repo.findLiveByEventAndSubscription(eventId, subscriptionId))
                .map(Delivery::deliveryId)
                .contains(second.get().deliveryId());
    }

    /**
     * Case 5: a different subscription_id for the same event_id inserts normally — the pair
     * is the key, not the event alone (the fan-out case).
     */
    @Test
    void insertIfAbsent_sameEventDifferentSubscription_insertsNormally() {
        String eventId = insertEvent(clientId);
        UUID otherSubscriptionId = insertSubscription(clientId);
        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Optional<Delivery> forFirstSub = repo.insertIfAbsent(
                buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()));
        Optional<Delivery> forSecondSub = repo.insertIfAbsent(
                buildDelivery(eventId, otherSubscriptionId, eventCreatedAt, Optional.empty()));

        assertThat(forFirstSub).isPresent();
        assertThat(forSecondSub).isPresent();
        assertThat(forFirstSub.get().deliveryId()).isNotEqualTo(forSecondSub.get().deliveryId());
    }

    /**
     * Case 6: findLiveByEventAndSubscription returns empty for a pair with no row, and for a
     * pair whose only row is terminal.
     */
    @Test
    void findLiveByEventAndSubscription_noRow_or_onlyTerminalRow_returnsEmpty() {
        String eventId = insertEvent(clientId);

        assertThat(repo.findLiveByEventAndSubscription(eventId, subscriptionId)).isEmpty();

        Instant eventCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery first = repo.insertIfAbsent(
                        buildDelivery(eventId, subscriptionId, eventCreatedAt, Optional.empty()))
                .orElseThrow();
        setStatusRaw(first.deliveryId(), "QUEUED");
        assertThat(repo.claimForProcessing(first.deliveryId(), Instant.now())).isTrue();
        assertThat(repo.markDelivered(first.deliveryId(), Instant.now())).isTrue();

        assertThat(repo.findLiveByEventAndSubscription(eventId, subscriptionId)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    private String insertEvent(String cid) {
        String eventId = "EVT-IDEMP-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", cid));
        return eventId;
    }

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }

    private Delivery buildDelivery(
            String eventId, UUID subId, Instant eventCreatedAt, Optional<String> traceContext) {
        return new Delivery(
                UUID.randomUUID(), eventId, subId, clientId,
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                eventCreatedAt, traceContext);
    }

    private void setStatusRaw(UUID deliveryId, String status) {
        jdbc.update(
                "UPDATE deliveries SET status = :status::delivery_status WHERE delivery_id = :id",
                new MapSqlParameterSource().addValue("status", status).addValue("id", deliveryId));
    }

    private int countRowsForPair(String eventId, UUID subId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM deliveries WHERE event_id = :eid AND subscription_id = :sid",
                new MapSqlParameterSource().addValue("eid", eventId).addValue("sid", subId),
                Integer.class);
        return count == null ? 0 : count;
    }
}
