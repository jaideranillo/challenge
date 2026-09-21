package com.cobre.challenge.adapter.in.web.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.adapter.in.web.ingest.dto.IngestEventResponse;
import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-005-17: proves ADR-002 §1.1's "if step 5 fails, the {@code deliveries} rows are already
 * durable" against a real forced SQS failure, not a stub.
 *
 * <p>The failure is injected against real LocalStack: the {@code deliveries} queue is deleted
 * after the application context (and {@code SqsNotificationQueueAdapter}, which resolves its
 * queue URL once at construction) has started, so the adapter's cached URL points at a queue
 * that no longer exists and the real SDK {@code SendMessage} call fails with a genuine
 * {@code QueueDoesNotExist} error. Neither {@link software.amazon.awssdk.services.sqs.SqsClient}
 * nor {@code NotificationQueuePort} is mocked or stubbed, and no production failure-injection
 * flag is added. The queue is recreated in teardown so the rest of the suite is unaffected.
 */
@Import({TestcontainersConfiguration.class, IngestPublishFailureAcceptanceTest.PermitIngestSecurityConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class IngestPublishFailureAcceptanceTest {

    @TestConfiguration
    static class PermitIngestSecurityConfig {
        @Bean
        SecurityFilterChain ingestTestFilterChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/internal/events/**")
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private SqsProperties sqsProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void deleteTheDeliveriesQueueAfterTheAdapterHasAlreadyResolvedItsUrl() {
        String queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveries())
                        .build())
                .queueUrl();
        sqsClient.deleteQueue(
                DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @AfterEach
    void restoreTheDeliveriesQueueSoTheRestOfTheSuiteIsUnaffected() {
        sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(sqsProperties.queues().deliveries())
                .build());
    }

    @Test
    void aForcedPublishFailureLeavesRowsDurableAndTheResponse202() throws Exception {
        String clientId = "client-fail-" + UUID.randomUUID();
        String eventType = "payment.created";
        UUID subscriptionId = insertVerifiedSubscription(clientId, eventType);
        String eventId = "EVT-" + UUID.randomUUID();

        double failuresBefore = currentFailureCount();
        Instant start = Instant.now();

        // Assertion 1: 202 with the normal body, deliveryIds populated, newlyCreated true.
        IngestEventResponse response = ingest(eventId, clientId, eventType, "content", Instant.now());
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(response.newlyCreated()).isTrue();
        assertThat(response.deliveryIds()).hasSize(1);

        // Assertion 2: the rows are durable despite the publish failing.
        List<Map<String, Object>> deliveries = readDeliveries(eventId);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("subscription_id")).isEqualTo(subscriptionId);
        assertThat(deliveries.get(0).get("status")).isEqualTo("PENDING");
        assertThat(countNotificationEvents(eventId)).isEqualTo(1);

        // Assertion 3: poll the counter with a bounded timeout; fail on timeout, not on a zero read.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(currentFailureCount()).isGreaterThanOrEqualTo(failuresBefore + 1));

        // Assertion 4: no exception surfaced (the ingest() helper above already asserts 202), and
        // the publish being off the request thread means the request did not pay for the failure.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));

        // Assertion 5: a follow-up ingest of the same event_id after the failure is still 202
        // and creates no duplicate rows.
        IngestEventResponse replay = ingest(eventId, clientId, eventType, "content", Instant.now());
        assertThat(replay.newlyCreated()).isFalse();
        assertThat(replay.deliveryIds()).isEqualTo(response.deliveryIds());
        assertThat(countNotificationEvents(eventId)).isEqualTo(1);
        assertThat(readDeliveries(eventId)).hasSize(1);
    }

    private double currentFailureCount() {
        try {
            return meterRegistry.get("notification.ingest.publish.failed").counter().count();
        } catch (MeterNotFoundException notYetRegistered) {
            return 0.0;
        }
    }

    private IngestEventResponse ingest(
            String eventId, String clientId, String eventType, String content, Instant occurredAt) throws Exception {
        String body =
                """
                {
                  "eventId": "%s",
                  "clientId": "%s",
                  "eventType": "%s",
                  "content": "%s",
                  "occurredAt": "%s"
                }
                """
                        .formatted(eventId, clientId, eventType, content, occurredAt);

        String responseBody = mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(responseBody, IngestEventResponse.class);
    }

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

    private int countNotificationEvents(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_events WHERE event_id = :eventId",
                new MapSqlParameterSource().addValue("eventId", eventId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> readDeliveries(String eventId) {
        return jdbc.queryForList(
                "SELECT * FROM deliveries WHERE event_id = :eventId",
                new MapSqlParameterSource().addValue("eventId", eventId));
    }
}
