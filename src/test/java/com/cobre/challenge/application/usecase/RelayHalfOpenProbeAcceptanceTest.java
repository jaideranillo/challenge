package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.adapter.out.messaging.dto.NotificationEnvelope;
import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * TASK-006-10: the Tech Lead's scenario 6, against real Postgres and real LocalStack SQS.
 *
 * <p>No mocks anywhere. {@code challenge.relay.enabled=false} keeps the live scheduler from
 * racing these fixtures; every cycle is driven directly through {@link
 * DispatchPendingDeliveriesUseCase#dispatch} with an explicit {@code asOf}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "challenge.relay.enabled=false")
class RelayHalfOpenProbeAcceptanceTest {

    private static final String TARGET_URL = "https://example.com/hook";

    @Autowired
    private DispatchPendingDeliveriesUseCase dispatchUseCase;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private SqsProperties sqsProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private String queueUrl;

    @BeforeEach
    void resolveQueueUrlAndDrainIt() {
        queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveries())
                        .build())
                .queueUrl();
        drainQueue();
    }

    // -----------------------------------------------------------------------
    // Scenarios 1 and 3: promotion, one probe admitted, the rule holds across cycles
    // -----------------------------------------------------------------------

    @Test
    void promotionAdmitsExactlyOneProbeAndTheRuleHoldsAcrossCycles() {
        Instant asOf = Instant.parse("2026-09-21T12:00:00Z");
        String clientId = "client-half-open-" + UUID.randomUUID();
        String eventType = "payment.created";

        UUID subscriptionId = insertOpenSubscription(
                clientId, eventType, 10, asOf.minus(Duration.ofMinutes(5)), Duration.ofMinutes(1), 3);
        List<UUID> deliveryIds = insertThreeDuePendingDeliveries(clientId, eventType, subscriptionId, asOf);

        Map<String, Object> subscriptionBefore = readSubscriptionRow(subscriptionId);

        DispatchPendingDeliveriesResult result =
                dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(50, asOf));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(1, 1));

        // Exactly one row QUEUED, the other two untouched PENDING with their original next_attempt_at.
        long queuedCount = deliveryIds.stream()
                .filter(id -> "QUEUED".equals(statusOf(id)))
                .count();
        long pendingCount = deliveryIds.stream()
                .filter(id -> "PENDING".equals(statusOf(id)))
                .count();
        assertThat(queuedCount).isEqualTo(1);
        assertThat(pendingCount).isEqualTo(2);

        UUID claimedDeliveryId = deliveryIds.stream()
                .filter(id -> "QUEUED".equals(statusOf(id)))
                .findFirst()
                .orElseThrow();
        assertThat(nextAttemptAtOf(claimedDeliveryId)).isEqualTo(asOf.plus(Duration.ofMinutes(5)));
        for (UUID id : deliveryIds) {
            if (!id.equals(claimedDeliveryId)) {
                assertThat(nextAttemptAtOf(id)).isEqualTo(asOf.minus(Duration.ofSeconds(10)));
            }
        }

        // promoteToHalfOpen writes circuit_state only (ADR-006 §1.2) - every other column is byte-for-byte unchanged.
        Map<String, Object> subscriptionAfter = readSubscriptionRow(subscriptionId);
        Map<String, Object> expected = new LinkedHashMap<>(subscriptionBefore);
        expected.put("circuit_state", "HALF_OPEN");
        assertThat(subscriptionAfter).isEqualTo(expected);

        List<Message> messages = receiveMessages();
        assertThat(messages).hasSize(1);
        assertThat(deliveryIdOf(messages.get(0))).isEqualTo(claimedDeliveryId);

        // Scenario 3: a second cycle, circuit now HALF_OPEN, admits no additional probe.
        Instant asOf2 = asOf.plusSeconds(1);
        DispatchPendingDeliveriesResult secondCycle =
                dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(50, asOf2));

        assertThat(secondCycle).isEqualTo(new DispatchPendingDeliveriesResult(0, 0));
        assertThat(statusOf(claimedDeliveryId)).isEqualTo("QUEUED");
        for (UUID id : deliveryIds) {
            if (!id.equals(claimedDeliveryId)) {
                assertThat(statusOf(id)).isEqualTo("PENDING");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 2: still cooling - no promotion, no claim
    // -----------------------------------------------------------------------

    @Test
    void stillCoolingCircuitAdmitsNoPromotionAndNoClaim() {
        Instant asOf = Instant.parse("2026-09-21T12:00:00Z");
        String clientId = "client-still-cooling-" + UUID.randomUUID();
        String eventType = "payment.created";

        UUID subscriptionId = insertOpenSubscription(
                clientId, eventType, 10, asOf.minus(Duration.ofSeconds(10)), Duration.ofMinutes(1), 1);
        insertThreeDuePendingDeliveries(clientId, eventType, subscriptionId, asOf);

        DispatchPendingDeliveriesResult result =
                dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(50, asOf));

        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(0, 0));
        assertThat(receiveMessages()).isEmpty();
        assertThat(readSubscriptionRow(subscriptionId).get("circuit_state")).isEqualTo("OPEN");
    }

    // -----------------------------------------------------------------------
    // Scenario 4: a CLOSED circuit is unaffected by the relay's blanket promoteToHalfOpen call
    // -----------------------------------------------------------------------

    @Test
    void closedCircuitSubscriptionIsUnaffectedByAConcurrentPromotion() {
        Instant asOf = Instant.parse("2026-09-21T12:00:00Z");
        String eventType = "payment.created";

        String openClientId = "client-half-open-2-" + UUID.randomUUID();
        UUID openSubscriptionId = insertOpenSubscription(
                openClientId, eventType, 10, asOf.minus(Duration.ofMinutes(5)), Duration.ofMinutes(1), 0);
        insertThreeDuePendingDeliveries(openClientId, eventType, openSubscriptionId, asOf);

        String closedClientId = "client-closed-" + UUID.randomUUID();
        UUID closedSubscriptionId = insertClosedSubscription(closedClientId, eventType, 2);
        List<UUID> closedDeliveryIds =
                insertThreeDuePendingDeliveries(closedClientId, eventType, closedSubscriptionId, asOf);

        Map<String, Object> closedBefore = readSubscriptionRow(closedSubscriptionId);

        DispatchPendingDeliveriesResult result =
                dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(50, asOf));

        // 1 probe from the OPEN subscription + the CLOSED subscription's max_concurrency of 2.
        assertThat(result).isEqualTo(new DispatchPendingDeliveriesResult(3, 3));

        long closedQueuedCount = closedDeliveryIds.stream()
                .filter(id -> "QUEUED".equals(statusOf(id)))
                .count();
        assertThat(closedQueuedCount).isEqualTo(2);

        assertThat(readSubscriptionRow(closedSubscriptionId)).isEqualTo(closedBefore);
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------

    private UUID insertOpenSubscription(
            String clientId,
            String eventType,
            int maxConcurrency,
            Instant circuitOpenedAt,
            Duration circuitBackoff,
            int consecutiveOpens) {
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions "
                        + "(subscription_id, client_id, target_url, secret_ref, event_types, active, verification_state, "
                        + " max_concurrency, circuit_state, circuit_opened_at, circuit_backoff, consecutive_opens) "
                        + "VALUES (:id, :clientId, :targetUrl, 'secret-ref', ARRAY[:eventType]::text[], true, "
                        + " 'VERIFIED'::verification_state, :maxConcurrency, 'OPEN'::circuit_state, :circuitOpenedAt, "
                        + " make_interval(secs => :circuitBackoffSeconds), :consecutiveOpens)",
                new MapSqlParameterSource()
                        .addValue("id", subscriptionId)
                        .addValue("clientId", clientId)
                        .addValue("targetUrl", TARGET_URL)
                        .addValue("eventType", eventType)
                        .addValue("maxConcurrency", maxConcurrency)
                        .addValue("circuitOpenedAt", OffsetDateTime.ofInstant(circuitOpenedAt, ZoneOffset.UTC))
                        .addValue("circuitBackoffSeconds", (double) circuitBackoff.toSeconds())
                        .addValue("consecutiveOpens", consecutiveOpens));
        return subscriptionId;
    }

    private UUID insertClosedSubscription(String clientId, String eventType, int maxConcurrency) {
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions "
                        + "(subscription_id, client_id, target_url, secret_ref, event_types, active, verification_state, "
                        + " max_concurrency) "
                        + "VALUES (:id, :clientId, :targetUrl, 'secret-ref', ARRAY[:eventType]::text[], true, "
                        + " 'VERIFIED'::verification_state, :maxConcurrency)",
                new MapSqlParameterSource()
                        .addValue("id", subscriptionId)
                        .addValue("clientId", clientId)
                        .addValue("targetUrl", TARGET_URL)
                        .addValue("eventType", eventType)
                        .addValue("maxConcurrency", maxConcurrency));
        return subscriptionId;
    }

    // Three PENDING rows, each its own event (the live-pair index forbids two live rows per
    // (event_id, subscription_id)), created/due older than the 30s grace window.
    private List<UUID> insertThreeDuePendingDeliveries(
            String clientId, String eventType, UUID subscriptionId, Instant asOf) {
        Instant createdAt = asOf.minus(Duration.ofSeconds(40));
        Instant nextAttemptAt = asOf.minus(Duration.ofSeconds(10));
        return List.of(
                insertPendingDelivery(clientId, eventType, subscriptionId, createdAt, nextAttemptAt),
                insertPendingDelivery(clientId, eventType, subscriptionId, createdAt, nextAttemptAt),
                insertPendingDelivery(clientId, eventType, subscriptionId, createdAt, nextAttemptAt));
    }

    private UUID insertPendingDelivery(
            String clientId, String eventType, UUID subscriptionId, Instant createdAt, Instant nextAttemptAt) {
        String eventId = insertNotificationEvent(clientId, eventType, createdAt);
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries "
                        + "(delivery_id, event_id, subscription_id, client_id, status, next_attempt_at, "
                        + " event_created_at, created_at, updated_at) "
                        + "VALUES (:deliveryId, :eventId, :subscriptionId, :clientId, 'PENDING'::delivery_status, "
                        + " :nextAttemptAt, :eventCreatedAt, :createdAt, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("deliveryId", deliveryId)
                        .addValue("eventId", eventId)
                        .addValue("subscriptionId", subscriptionId)
                        .addValue("clientId", clientId)
                        .addValue("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                        .addValue("eventCreatedAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                        .addValue("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
        return deliveryId;
    }

    private String insertNotificationEvent(String clientId, String eventType, Instant createdAt) {
        String eventId = "EVT-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:eventId, :clientId, :eventType, 'content', :createdAt)",
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("clientId", clientId)
                        .addValue("eventType", eventType)
                        .addValue("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
        return eventId;
    }

    // -----------------------------------------------------------------------
    // reads
    // -----------------------------------------------------------------------

    // Casts array/interval columns to text: their driver types have no value-based equals, so a
    // raw SELECT * would compare object identity across two reads instead of column content.
    private Map<String, Object> readSubscriptionRow(UUID subscriptionId) {
        return jdbc.queryForMap(
                "SELECT subscription_id, client_id, target_url, secret_ref, previous_secret_ref, "
                        + " previous_secret_expires_at, array_to_string(event_types, ',') AS event_types, active, "
                        + " verification_state, verified_at, max_concurrency, circuit_state, circuit_opened_at, "
                        + " circuit_backoff::text AS circuit_backoff, consecutive_opens, throttled_until, "
                        + " created_at, updated_at "
                        + "FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", subscriptionId));
    }

    private String statusOf(UUID deliveryId) {
        return jdbc.queryForObject(
                "SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                String.class);
    }

    private Instant nextAttemptAtOf(UUID deliveryId) {
        return jdbc.queryForObject(
                "SELECT next_attempt_at FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant());
    }

    // -----------------------------------------------------------------------
    // queue
    // -----------------------------------------------------------------------

    private void drainQueue() {
        List<Message> messages = receiveMessages();
        while (!messages.isEmpty()) {
            for (Message message : messages) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            }
            messages = receiveMessages();
        }
    }

    private List<Message> receiveMessages() {
        return sqsClient
                .receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .build())
                .messages();
    }

    private UUID deliveryIdOf(Message message) {
        return objectMapper.readValue(message.body(), NotificationEnvelope.class).deliveryId();
    }
}
