package com.cobre.challenge.adapter.in.web.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.adapter.in.web.ingest.dto.IngestEventResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-005-16: end-to-end proof of two of the three Tech-Lead-named acceptance cases, through
 * the real HTTP endpoint, real Postgres and real LocalStack (no mocked SQS, no H2, no stubbed
 * port — CLAUDE.md).
 *
 * <p>{@code /internal/events} ships unauthenticated by explicit scope cut (see
 * {@code docs/concerns.md}). No production {@code SecurityFilterChain} is added here; the
 * nested {@link PermitIngestSecurityConfig} is test-scoped only, exactly like
 * {@code LocalWebhookStubSecurityConfigTest}'s established pattern.
 */
@Import({TestcontainersConfiguration.class, EventIngestAcceptanceTest.PermitIngestSecurityConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class EventIngestAcceptanceTest {

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

    // -----------------------------------------------------------------------
    // Case A: replaying the same event_id creates no duplicate rows
    // -----------------------------------------------------------------------

    @Test
    void replayingTheSameEventIdCreatesNoDuplicateRows() throws Exception {
        String clientId = "client-a-" + UUID.randomUUID();
        String eventType = "payment.created";
        insertVerifiedSubscription(clientId, eventType);
        insertVerifiedSubscription(clientId, eventType);
        String eventId = "EVT-" + UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-20T10:00:00Z");

        IngestEventResponse first = ingest(eventId, clientId, eventType, "first-content", occurredAt);
        IngestEventResponse second = ingest(eventId, clientId, eventType, "first-content", occurredAt);

        assertThat(first.newlyCreated()).isTrue();
        assertThat(second.newlyCreated()).isFalse();
        assertThat(Set.copyOf(second.deliveryIds())).isEqualTo(Set.copyOf(first.deliveryIds()));

        assertThat(countNotificationEvents(eventId)).isEqualTo(1);
        Map<String, Object> storedEvent = readNotificationEvent(eventId);
        assertThat(storedEvent.get("content")).isEqualTo("first-content");

        assertThat(countDeliveries(eventId)).isEqualTo(2);
        List<Map<String, Object>> deliveriesBefore = readDeliveries(eventId);

        // Re-ingest with a different occurredAt/content: the stored event and both
        // deliveries' event_created_at must still be the first request's (FEAT-005 decision 5).
        Instant differentOccurredAt = occurredAt.plusSeconds(3600);
        IngestEventResponse third = ingest(eventId, clientId, eventType, "different-content", differentOccurredAt);

        assertThat(third.newlyCreated()).isFalse();
        assertThat(countNotificationEvents(eventId)).isEqualTo(1);
        Map<String, Object> storedEventAfterThird = readNotificationEvent(eventId);
        assertThat(storedEventAfterThird.get("content")).isEqualTo("first-content");

        List<Map<String, Object>> deliveriesAfter = readDeliveries(eventId);
        assertThat(deliveriesAfter).hasSameSizeAs(deliveriesBefore);
        for (Map<String, Object> before : deliveriesBefore) {
            Map<String, Object> after = deliveriesAfter.stream()
                    .filter(row -> row.get("delivery_id").equals(before.get("delivery_id")))
                    .findFirst()
                    .orElseThrow();
            assertThat(after.get("attempt_count")).isEqualTo(before.get("attempt_count"));
            assertThat(after.get("status")).isEqualTo(before.get("status"));
            assertThat(after.get("event_created_at")).isEqualTo(before.get("event_created_at"));
        }
    }

    // -----------------------------------------------------------------------
    // Case B: a client with no matching subscription
    // -----------------------------------------------------------------------

    @Test
    void clientWithAnActiveSubscriptionForADifferentEventTypeStillGets202WithZeroDeliveries() throws Exception {
        String clientId = "client-b1-" + UUID.randomUUID();
        insertVerifiedSubscription(clientId, "some.other.type");
        String eventId = "EVT-" + UUID.randomUUID();

        IngestEventResponse response = ingest(eventId, clientId, "payment.created", "content", Instant.now());

        assertThat(response.deliveryIds()).isEmpty();
        assertThat(countNotificationEvents(eventId)).isEqualTo(1);
        assertThat(countDeliveries(eventId)).isEqualTo(0);
    }

    @Test
    void clientWithNoSubscriptionAtAllStillGets202WithZeroDeliveries() throws Exception {
        String clientId = "client-b2-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();

        IngestEventResponse response = ingest(eventId, clientId, "payment.created", "content", Instant.now());

        assertThat(response.deliveryIds()).isEmpty();
        assertThat(countNotificationEvents(eventId)).isEqualTo(1);
        assertThat(countDeliveries(eventId)).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Case C: the fan-out does not cross tenants
    // -----------------------------------------------------------------------

    @Test
    void fanOutDoesNotCrossTenants() throws Exception {
        String eventType = "payment.created";
        String clientX = "client-x-" + UUID.randomUUID();
        String clientY = "client-y-" + UUID.randomUUID();
        UUID subscriptionX = insertVerifiedSubscription(clientX, eventType);
        insertVerifiedSubscription(clientY, eventType);
        String eventId = "EVT-" + UUID.randomUUID();

        ingest(eventId, clientX, eventType, "content", Instant.now());

        List<Map<String, Object>> deliveries = readDeliveries(eventId);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("subscription_id")).isEqualTo(subscriptionX);
        assertThat(deliveries.get(0).get("client_id")).isEqualTo(clientX);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

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

    private int countDeliveries(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM deliveries WHERE event_id = :eventId",
                new MapSqlParameterSource().addValue("eventId", eventId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> readNotificationEvent(String eventId) {
        return jdbc.queryForMap(
                "SELECT * FROM notification_events WHERE event_id = :eventId",
                new MapSqlParameterSource().addValue("eventId", eventId));
    }

    private List<Map<String, Object>> readDeliveries(String eventId) {
        return jdbc.queryForList(
                "SELECT * FROM deliveries WHERE event_id = :eventId",
                new MapSqlParameterSource().addValue("eventId", eventId));
    }
}
