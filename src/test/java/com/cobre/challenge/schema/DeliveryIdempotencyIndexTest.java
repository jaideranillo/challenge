package com.cobre.challenge.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cobre.challenge.TestcontainersConfiguration;

/**
 * ADR-003 §2 idempotency invariant: at most one non-terminal {@code deliveries}
 * row per {@code (event_id, subscription_id)} pair, enforced by the native
 * partial unique index {@code idx_deliveries_live_pair} on {@code deliveries}
 * itself (V2 migration). No side table, no trigger - this test exercises the
 * mechanism through plain SQL, not its internals.
 *
 * Each test method uses its own freshly generated event_id/subscription_id
 * pair rather than @Transactional rollback: a Postgres transaction that hits
 * the expected 23505 unique violation is left aborted for any further
 * statement in that same transaction, which would complicate asserting-then-
 * continuing within one test method. Unique-per-test data sidesteps that.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryIdempotencyIndexTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String insertEvent(String clientId) {
        String eventId = "EVT-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (?, ?, 'payment.completed', 'test-content', now())",
                eventId, clientId);
        return eventId;
    }

    private UUID insertSubscription(String clientId) {
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (?, ?, 'https://example.com/webhook', 'secret-ref', ARRAY['payment.completed']::text[])",
                subscriptionId, clientId);
        return subscriptionId;
    }

    private void insertDelivery(UUID deliveryId, String eventId, UUID subscriptionId, String clientId, String status) {
        jdbcTemplate.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status) "
                        + "VALUES (?, ?, ?, ?, ?::delivery_status)",
                deliveryId, eventId, subscriptionId, clientId, status);
    }

    private void moveToStatus(UUID deliveryId, String status) {
        jdbcTemplate.update(
                "UPDATE deliveries SET status = ?::delivery_status, next_attempt_at = NULL WHERE delivery_id = ?",
                status, deliveryId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "QUEUED", "PROCESSING", "RETRYING"})
    void secondLiveRowForSamePairIsRejected(String liveStatus) {
        String clientId = "client-" + UUID.randomUUID();
        String eventId = insertEvent(clientId);
        UUID subscriptionId = insertSubscription(clientId);

        insertDelivery(UUID.randomUUID(), eventId, subscriptionId, clientId, liveStatus);

        DataIntegrityViolationException ex = catchThrowableOfType(
                () -> insertDelivery(UUID.randomUUID(), eventId, subscriptionId, clientId, liveStatus),
                DataIntegrityViolationException.class);

        assertThat(ex).isNotNull();
        Throwable rootCause = ex.getMostSpecificCause();
        assertThat(rootCause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23505");
        assertThat(rootCause.getMessage()).contains("idx_deliveries_live_pair");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERED", "DEAD", "FAILED"})
    void secondRowAllowedOnceFirstIsTerminal(String terminalStatus) {
        String clientId = "client-" + UUID.randomUUID();
        String eventId = insertEvent(clientId);
        UUID subscriptionId = insertSubscription(clientId);

        UUID firstDeliveryId = UUID.randomUUID();
        insertDelivery(firstDeliveryId, eventId, subscriptionId, clientId, "PENDING");
        moveToStatus(firstDeliveryId, terminalStatus);

        assertThatCode(() -> insertDelivery(UUID.randomUUID(), eventId, subscriptionId, clientId, "PENDING"))
                .doesNotThrowAnyException();
    }

    @Test
    void fullTransitionRejectsWhileLiveThenAcceptsAfterTerminal() {
        String clientId = "client-" + UUID.randomUUID();
        String eventId = insertEvent(clientId);
        UUID subscriptionId = insertSubscription(clientId);

        UUID firstDeliveryId = UUID.randomUUID();
        insertDelivery(firstDeliveryId, eventId, subscriptionId, clientId, "PENDING");

        DataIntegrityViolationException ex = catchThrowableOfType(
                () -> insertDelivery(UUID.randomUUID(), eventId, subscriptionId, clientId, "QUEUED"),
                DataIntegrityViolationException.class);
        assertThat(ex).isNotNull();
        assertThat(((SQLException) ex.getMostSpecificCause()).getSQLState()).isEqualTo("23505");

        moveToStatus(firstDeliveryId, "DEAD");

        assertThatCode(() -> insertDelivery(UUID.randomUUID(), eventId, subscriptionId, clientId, "PENDING"))
                .doesNotThrowAnyException();
    }
}
