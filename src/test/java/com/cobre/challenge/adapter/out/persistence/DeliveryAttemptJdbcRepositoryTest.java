package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.DeliveryAttempt;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Integration tests for {@link DeliveryAttemptJdbcRepository}.
 *
 * <p>Append-only invariant: no update or delete paths. Real Postgres via {@link TestcontainersConfiguration}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryAttemptJdbcRepositoryTest {

    @Autowired
    DeliveryAttemptJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;
    private UUID deliveryId;

    @BeforeEach
    void fixtures() {
        clientId = "client-attempt-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
        deliveryId = insertDelivery(clientId, subscriptionId);
    }

    // -----------------------------------------------------------------------
    // insert / round trip
    // -----------------------------------------------------------------------

    /** Test 1: full round trip, including null http_status/response_excerpt/error. */
    @Test
    void insert_fullRoundTrip_allComponentsPreserved() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DeliveryAttempt attempt = new DeliveryAttempt(
                deliveryId, 1, OptionalInt.empty(), 0, Optional.empty(), Optional.empty(), now);

        repo.insert(attempt);

        List<DeliveryAttempt> found = repo.findByDeliveryId(deliveryId);
        assertThat(found).hasSize(1);
        DeliveryAttempt stored = found.get(0);
        assertThat(stored.deliveryId()).isEqualTo(deliveryId);
        assertThat(stored.attemptNumber()).isEqualTo(1);
        assertThat(stored.httpStatus()).isEmpty();
        assertThat(stored.responseTimeMs()).isEqualTo(0);
        assertThat(stored.responseExcerpt()).isEmpty();
        assertThat(stored.error()).isEmpty();
        assertThat(stored.attemptedAt()).isEqualTo(now);
    }

    /** Test 2: successful attempt with HTTP status and response excerpt persists and is findable. */
    @Test
    void insert_successfulAttemptWithHttpStatus_persistsAndIsReturned() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DeliveryAttempt attempt = new DeliveryAttempt(
                deliveryId, 1, OptionalInt.of(200), 42, Optional.of("ok body"), Optional.empty(), now);

        repo.insert(attempt);

        List<DeliveryAttempt> found = repo.findByDeliveryId(deliveryId);
        assertThat(found).hasSize(1);
        DeliveryAttempt stored = found.get(0);
        assertThat(stored.httpStatus()).hasValue(200);
        assertThat(stored.responseExcerpt()).hasValue("ok body");
        assertThat(stored.responseTimeMs()).isEqualTo(42);
    }

    /** Test 3: network timeout (no HTTP status, error present). */
    @Test
    void insert_networkTimeout_httpStatusAbsentErrorPresent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DeliveryAttempt attempt = new DeliveryAttempt(
                deliveryId, 1, OptionalInt.empty(), 0, Optional.empty(),
                Optional.of("connection refused"), now);

        repo.insert(attempt);

        List<DeliveryAttempt> found = repo.findByDeliveryId(deliveryId);
        assertThat(found.get(0).httpStatus()).isEmpty();
        assertThat(found.get(0).error()).hasValue("connection refused");
    }

    /** Test 4: responseExcerpt > 1000 chars is truncated to 1000 before binding. */
    @Test
    void insert_longResponseExcerpt_truncatedTo1000() {
        String longExcerpt = "x".repeat(1500);
        DeliveryAttempt attempt = new DeliveryAttempt(
                deliveryId, 1, OptionalInt.of(500), 100, Optional.of(longExcerpt),
                Optional.empty(), Instant.now());

        repo.insert(attempt);

        List<DeliveryAttempt> found = repo.findByDeliveryId(deliveryId);
        assertThat(found.get(0).responseExcerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt).hasSize(1000));
    }

    /** Test 5: id is identity, never bound or echoed by insert; two attempts get distinct ids. */
    @Test
    void insert_identityIdNeverBound_distinctPerRow() {
        repo.insert(new DeliveryAttempt(deliveryId, 1, OptionalInt.of(200), 5,
                Optional.of("first"), Optional.empty(), Instant.now().minus(1, ChronoUnit.MINUTES)));
        repo.insert(new DeliveryAttempt(deliveryId, 2, OptionalInt.of(200), 5,
                Optional.of("second"), Optional.empty(), Instant.now()));

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM delivery_attempts WHERE delivery_id = :id ORDER BY id",
                new MapSqlParameterSource().addValue("id", deliveryId), Long.class);
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }

    // -----------------------------------------------------------------------
    // findByDeliveryId
    // -----------------------------------------------------------------------

    /** Test 6: attempts returned ordered by attempted_at ascending, regardless of insert order. */
    @Test
    void findByDeliveryId_multipleAttempts_orderedByAttemptedAtAsc() {
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        repo.insert(new DeliveryAttempt(deliveryId, 3, OptionalInt.of(500), 1,
                Optional.of("3rd"), Optional.empty(), t3));
        repo.insert(new DeliveryAttempt(deliveryId, 1, OptionalInt.of(500), 1,
                Optional.of("1st"), Optional.empty(), t1));
        repo.insert(new DeliveryAttempt(deliveryId, 2, OptionalInt.of(500), 1,
                Optional.of("2nd"), Optional.empty(), t2));

        List<DeliveryAttempt> found = repo.findByDeliveryId(deliveryId);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).responseExcerpt()).hasValue("1st");
        assertThat(found.get(1).responseExcerpt()).hasValue("2nd");
        assertThat(found.get(2).responseExcerpt()).hasValue("3rd");
    }

    /** Test 7: no attempts returns empty list, not null. */
    @Test
    void findByDeliveryId_noAttempts_returnsEmptyList() {
        assertThat(repo.findByDeliveryId(deliveryId)).isNotNull().isEmpty();
    }

    /** Test 8: non-existent delivery_id returns empty list without throwing. */
    @Test
    void findByDeliveryId_nonExistentDelivery_returnsEmptyList() {
        assertThat(repo.findByDeliveryId(UUID.randomUUID())).isNotNull().isEmpty();
    }

    // -----------------------------------------------------------------------
    // Cross-cutting
    // -----------------------------------------------------------------------

    /**
     * Test 9: append-only invariant — no update or delete is possible;
     * only insert and read exist on the port. Inserting twice leaves both rows present.
     */
    @Test
    void appendOnlyInvariant_insertTwice_twoRowsPresent() {
        repo.insert(new DeliveryAttempt(deliveryId, 1, OptionalInt.of(200), 1,
                Optional.of("first"), Optional.empty(), Instant.now().minus(1, ChronoUnit.MINUTES)));
        repo.insert(new DeliveryAttempt(deliveryId, 2, OptionalInt.of(200), 1,
                Optional.of("second"), Optional.empty(), Instant.now()));

        assertThat(repo.findByDeliveryId(deliveryId)).hasSize(2);
    }

    /** Test 10: an attempt against a non-existent delivery_id violates the FK. */
    @Test
    void insert_unknownDeliveryId_violatesForeignKey() {
        DeliveryAttempt attempt = new DeliveryAttempt(
                UUID.randomUUID(), 1, OptionalInt.of(200), 1, Optional.of("orphan"),
                Optional.empty(), Instant.now());

        assertThatThrownBy(() -> repo.insert(attempt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertDelivery(String cid, UUID sid) {
        String eventId = "EVT-ATT-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", cid));
        UUID dId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, now())",
                new MapSqlParameterSource()
                        .addValue("id", dId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", cid));
        return dId;
    }

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }
}
