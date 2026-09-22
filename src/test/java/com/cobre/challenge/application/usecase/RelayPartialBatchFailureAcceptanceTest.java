package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import com.cobre.challenge.application.port.out.persistence.DeliveryPipelineRepositoryPort;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryOrigin;
import com.cobre.challenge.domain.model.delivery.enums.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * TASK-006-11 / Tech Lead scenario 7: entries SQS refuses stay {@code QUEUED} with their pushed
 * {@code next_attempt_at} and are picked up again only once that clock elapses - no compensation,
 * no rollback, no status difference between a published row and a refused one.
 *
 * <p>Real PostgreSQL and real LocalStack SQS via {@link TestcontainersConfiguration}. No mocks,
 * no stub port. {@code challenge.relay.enabled=false} so the live scheduler cannot race the
 * fixtures; {@link DispatchPendingDeliveriesUseCase#dispatch} is driven directly with an explicit
 * {@code asOf} on every cycle.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "challenge.relay.enabled=false")
class RelayPartialBatchFailureAcceptanceTest {

    // Control char 0x01 is outside XML's legal character ranges, so SQS refuses only the entry
    // carrying it as a message attribute value - and unlike NUL, Postgres text can store it.
    private static final String ILLEGAL_TRACE_CONTEXT = "00--bad-01";

    @Autowired
    private DispatchPendingDeliveriesUseCase dispatchUseCase;

    @Autowired
    private DeliveryPipelineRepositoryPort pipelinePort;

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
    void resolveQueueUrlAndDrain() {
        queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveries())
                        .build())
                .queueUrl();
        drainQueue();
    }

    @Test
    void refusedEntriesStayQueuedAndRecoverOnceThePushedClockElapses() {
        // asOf is offset well into the future so every fixture row's real insert-time created_at
        // trivially satisfies the PENDING grace predicate (created_at < asOf - 30s); this is a
        // fixture convenience, not a wall-clock assertion - every assertion below is against asOf.
        Instant asOf = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        String clientId = "client-relay-partial-" + UUID.randomUUID();
        String eventType = "payment.created";
        UUID subscriptionId = insertVerifiedSubscription(clientId, eventType);

        List<UUID> refusedIds = List.of(
                insertDueDelivery(subscriptionId, clientId, eventType, Optional.of(ILLEGAL_TRACE_CONTEXT)),
                insertDueDelivery(subscriptionId, clientId, eventType, Optional.of(ILLEGAL_TRACE_CONTEXT)));
        List<UUID> goodIds = List.of(
                insertDueDelivery(subscriptionId, clientId, eventType, Optional.of("00-good-trace-01")),
                insertDueDelivery(subscriptionId, clientId, eventType, Optional.of("00-good-trace-02")),
                insertDueDelivery(subscriptionId, clientId, eventType, Optional.of("00-good-trace-03")));
        List<UUID> allIds = new ArrayList<>();
        allIds.addAll(refusedIds);
        allIds.addAll(goodIds);

        // Cycle 1 at asOf: two of five entries are refused by SQS.
        DispatchPendingDeliveriesResult cycle1 = dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(10, asOf));

        assertThat(cycle1).isEqualTo(new DispatchPendingDeliveriesResult(5, 3, List.of()));

        // Heart of the scenario: every row, published and refused alike, is QUEUED with the
        // pushed clock - no status difference, no compensation.
        Instant expectedPush1 = asOf.plus(Duration.ofMinutes(5));
        for (UUID deliveryId : allIds) {
            assertThat(readStatus(deliveryId)).isEqualTo("QUEUED");
            assertThat(readOdtColumn(deliveryId, "next_attempt_at").toInstant()).isEqualTo(expectedPush1);
            assertThat(readIntColumn(deliveryId, "attempt_count")).isEqualTo(0);
            assertThat(countDeliveryAttempts(deliveryId)).isZero();
        }

        Set<String> received1 = awaitDeliveryIds(3);
        assertThat(received1).isEqualTo(idStrings(goodIds));

        // Cycle 1b, the control: before the pushed clock elapses, nothing is claimable.
        DispatchPendingDeliveriesResult control =
                dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(10, asOf.plus(Duration.ofMinutes(1))));

        assertThat(control).isEqualTo(new DispatchPendingDeliveriesResult(0, 0, List.of()));

        // Repair the refused rows so they can publish, then let the pushed clock elapse.
        for (UUID deliveryId : refusedIds) {
            jdbc.update(
                    "UPDATE deliveries SET trace_context = :trace WHERE delivery_id = :id",
                    new MapSqlParameterSource().addValue("trace", "00-repaired-01").addValue("id", deliveryId));
        }
        Instant asOf2 = asOf.plus(Duration.ofMinutes(5)).plusSeconds(1);

        // Cycle 2 at asOf2: the row, not the entry, is what recovers - the pushed clock is the
        // sole recovery mechanism.
        DispatchPendingDeliveriesResult cycle2 = dispatchUseCase.dispatch(new DispatchPendingDeliveriesCommand(10, asOf2));

        assertThat(cycle2.claimedCount()).isGreaterThanOrEqualTo(refusedIds.size());

        Instant expectedPush2 = asOf2.plus(Duration.ofMinutes(5));
        for (UUID deliveryId : refusedIds) {
            assertThat(readStatus(deliveryId)).isEqualTo("QUEUED");
            assertThat(readOdtColumn(deliveryId, "next_attempt_at").toInstant()).isEqualTo(expectedPush2);
            assertThat(readIntColumn(deliveryId, "attempt_count")).isEqualTo(0);
            assertThat(countDeliveryAttempts(deliveryId)).isZero();
        }

        Set<String> received2 = awaitDeliveryIds(cycle2.publishedCount());
        assertThat(received2).containsAll(idStrings(refusedIds));
    }

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    private UUID insertVerifiedSubscription(String clientId, String eventType) {
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions "
                        + "(subscription_id, client_id, target_url, secret_ref, event_types, active, verification_state) "
                        + "VALUES (:id, :clientId, 'https://example.com/hook', 'secret-ref', ARRAY[:eventType]::text[], "
                        + "true, 'VERIFIED'::verification_state)",
                new MapSqlParameterSource()
                        .addValue("id", subscriptionId)
                        .addValue("clientId", clientId)
                        .addValue("eventType", eventType));
        return subscriptionId;
    }

    // Each due row gets its own notification_event: idx_deliveries_live_pair (V2) admits at most
    // one live row per (event_id, subscription_id) pair, so five rows on one subscription need
    // five distinct events.
    private UUID insertDueDelivery(UUID subscriptionId, String clientId, String eventType, Optional<String> traceContext) {
        String eventId = "EVT-RELAY-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :clientId, :eventType, 'content', now())",
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("clientId", clientId)
                        .addValue("eventType", eventType));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Delivery delivery = new Delivery(
                UUID.randomUUID(), eventId, subscriptionId, clientId,
                DeliveryStatus.PENDING, DeliveryOrigin.INGEST,
                Optional.empty(), 0, Optional.of(now), Optional.empty(), Optional.empty(),
                now, traceContext);

        return pipelinePort.insert(delivery).deliveryId();
    }

    private String readStatus(UUID deliveryId) {
        return jdbc.queryForObject(
                "SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), String.class);
    }

    private Integer readIntColumn(UUID deliveryId, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), Integer.class);
    }

    private OffsetDateTime readOdtColumn(UUID deliveryId, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId),
                (rs, n) -> rs.getObject(col, OffsetDateTime.class));
    }

    private int countDeliveryAttempts(UUID deliveryId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE delivery_id = :id",
                new MapSqlParameterSource("id", deliveryId), Integer.class);
        return count == null ? 0 : count;
    }

    private Set<String> idStrings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    // Queue helpers
    // -----------------------------------------------------------------------

    private void drainQueue() {
        List<Message> messages;
        do {
            messages = receiveBatch();
            for (Message message : messages) {
                sqsClient.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            }
        } while (!messages.isEmpty());
    }

    private Set<String> awaitDeliveryIds(int expectedCount) {
        Set<String> receivedDeliveryIds = new HashSet<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            for (Message message : receiveBatch()) {
                ObjectNode body = (ObjectNode) objectMapper.readTree(message.body());
                receivedDeliveryIds.add(body.get("deliveryId").asString());
                sqsClient.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            }
            return receivedDeliveryIds.size() == expectedCount;
        });
        return receivedDeliveryIds;
    }

    private List<Message> receiveBatch() {
        return sqsClient
                .receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .messageAttributeNames("All")
                        .waitTimeSeconds(1)
                        .build())
                .messages();
    }
}
