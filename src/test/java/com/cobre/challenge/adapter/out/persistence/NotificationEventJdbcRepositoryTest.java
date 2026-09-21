package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link NotificationEventJdbcRepository}: proves the
 * {@code ON CONFLICT (event_id) DO NOTHING} idempotency mechanism (ADR-002 §1.1 step 3) against
 * a real Postgres.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}, Flyway-migrated. No H2, no mocks.
 *
 * <p>{@code @ActiveProfiles("local")} is required for the Spring context to load: the SQS client
 * bean (unrelated to this test) needs {@code challenge.sqs.queues}, which only the {@code local}
 * profile supplies; {@link TestcontainersConfiguration}'s LocalStack registrar does not set it.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@SpringBootTest
class NotificationEventJdbcRepositoryTest {

    @Autowired
    NotificationEventJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /** Case 1: first insert returns true and every column round-trips. */
    @Test
    void insertIfAbsent_firstInsert_returnsTrue_roundTripsAllColumns() {
        String eventId = "EVT-" + UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        NotificationEvent event = new NotificationEvent(eventId, "client-1", "payment.completed", "payload-A", createdAt);

        boolean inserted = repo.insertIfAbsent(event);

        assertThat(inserted).isTrue();
        NotificationEvent stored = repo.findById(eventId).orElseThrow();
        assertThat(stored.eventId()).isEqualTo(eventId);
        assertThat(stored.clientId()).isEqualTo("client-1");
        assertThat(stored.eventType()).isEqualTo("payment.completed");
        assertThat(stored.content()).isEqualTo("payload-A");
        assertThat(stored.createdAt()).isEqualTo(createdAt);
    }

    /** Case 2: second insert of the same event_id returns false and throws nothing. */
    @Test
    void insertIfAbsent_secondInsertSameId_returnsFalse_noException() {
        String eventId = "EVT-" + UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repo.insertIfAbsent(new NotificationEvent(eventId, "client-1", "payment.completed", "payload-A", createdAt));

        boolean second = repo.insertIfAbsent(
                new NotificationEvent(eventId, "client-2", "payment.failed", "payload-B", createdAt.plusSeconds(60)));

        assertThatCode(() -> assertThat(second).isFalse()).doesNotThrowAnyException();
    }

    /**
     * Case 3: the second insert is non-destructive — every column still holds the first
     * insert's values, and the table holds exactly one row for the event.
     */
    @Test
    void insertIfAbsent_secondInsert_isNonDestructive() {
        String eventId = "EVT-" + UUID.randomUUID();
        Instant firstCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repo.insertIfAbsent(new NotificationEvent(eventId, "client-1", "payment.completed", "A", firstCreatedAt));

        repo.insertIfAbsent(new NotificationEvent(
                eventId, "client-2", "payment.failed", "B", firstCreatedAt.plus(30, ChronoUnit.DAYS)));

        NotificationEvent stored = repo.findById(eventId).orElseThrow();
        assertThat(stored.clientId()).isEqualTo("client-1");
        assertThat(stored.eventType()).isEqualTo("payment.completed");
        assertThat(stored.content()).isEqualTo("A");
        assertThat(stored.createdAt()).isEqualTo(firstCreatedAt);

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM notification_events WHERE event_id = :id",
                new MapSqlParameterSource("id", eventId), Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    /** Case 4: findById returns empty for an unknown event_id and does not throw. */
    @Test
    void findById_unknownEventId_returnsEmpty() {
        assertThat(repo.findById("EVT-does-not-exist-" + UUID.randomUUID())).isEmpty();
    }

    /** Case 5: findById returns the stored row for a known id, createdAt matching case 1's value. */
    @Test
    void findById_knownEventId_returnsStoredRow() {
        String eventId = "EVT-" + UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repo.insertIfAbsent(new NotificationEvent(eventId, "client-1", "payment.completed", "payload-A", createdAt));

        NotificationEvent stored = repo.findById(eventId).orElseThrow();

        assertThat(stored.createdAt()).isEqualTo(createdAt);
    }
}
