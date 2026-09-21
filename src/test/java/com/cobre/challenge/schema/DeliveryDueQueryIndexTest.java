package com.cobre.challenge.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-002 §2.1: the relay's due-query must use {@code idx_deliveries_due}
 * (V2 migration), not a sequential scan of {@code deliveries}.
 *
 * {@code deliveries} is a single, unpartitioned table (ADR-003 §3), so the
 * plan has no per-partition nodes, no Merge Append, and no auto-generated
 * child index names - the index node names idx_deliveries_due directly.
 *
 * Seed size and status mix: 5,000 deliveries rows across 25 subscriptions /
 * 5 clients - 250 live (evenly split PENDING/QUEUED/PROCESSING/RETRYING,
 * next_attempt_at 60s in the past so they are due) and 4,750 terminal
 * (evenly split DELIVERED/DEAD/FAILED, next_attempt_at NULL, matching the
 * real terminal-transition writers). 5% live / 95% terminal, skewed toward
 * terminal to reflect ADR-002 §2.1's own framing ("the table may hold tens
 * of millions of rows while the index holds only what is outstanding") at a
 * scale a unit test can seed in seconds. ANALYZE is run before EXPLAIN so
 * the planner has real statistics, and enable_seqscan is never touched -
 * the question is whether the planner chooses the index, not whether it can.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(Lifecycle.PER_CLASS)
class DeliveryDueQueryIndexTest {

    private static final int LIVE_COUNT = 250;
    private static final int TERMINAL_COUNT = 4750;
    private static final String[] LIVE_STATUSES = {"PENDING", "QUEUED", "PROCESSING", "RETRYING"};
    private static final String[] TERMINAL_STATUSES = {"DELIVERED", "DEAD", "FAILED"};

    private static final String DUE_QUERY = """
            SELECT d.* FROM deliveries d
            JOIN subscriptions s ON s.subscription_id = d.subscription_id
            WHERE d.status IN ('PENDING', 'RETRYING', 'QUEUED', 'PROCESSING')
              AND d.next_attempt_at <= now()
              AND (d.status <> 'PENDING' OR d.created_at < now() - interval '30 seconds')
              AND (d.status <> 'PROCESSING' OR d.updated_at < now() - interval '60 seconds')
              AND (s.circuit_state <> 'OPEN' OR s.circuit_opened_at < now() - s.circuit_backoff)
              AND (s.throttled_until IS NULL OR s.throttled_until <= now())
            ORDER BY d.next_attempt_at
            LIMIT 500
            FOR UPDATE SKIP LOCKED
            """;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seedRealisticTable() {
        List<UUID> subscriptionIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String clientId = "client-" + (i % 5);
            UUID subscriptionId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                            + "VALUES (?, ?, 'https://example.com/webhook', 'secret-ref', ARRAY['payment.completed']::text[])",
                    subscriptionId, clientId);
            subscriptionIds.add(subscriptionId);
        }

        int total = LIVE_COUNT + TERMINAL_COUNT;
        List<Object[]> eventBatch = new ArrayList<>(total);
        List<Object[]> deliveryBatch = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            String eventId = "EVT-DUE-" + i;
            UUID subscriptionId = subscriptionIds.get(i % subscriptionIds.size());
            String clientId = "client-" + (i % 5);

            eventBatch.add(new Object[] {eventId, clientId});

            String status;
            boolean live = i < LIVE_COUNT;
            if (live) {
                status = LIVE_STATUSES[i % LIVE_STATUSES.length];
            } else {
                status = TERMINAL_STATUSES[(i - LIVE_COUNT) % TERMINAL_STATUSES.length];
            }

            deliveryBatch.add(new Object[] {
                    UUID.randomUUID(), eventId, subscriptionId, clientId, status, live
            });
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (?, ?, 'payment.completed', 'test-content', now() - interval '5 minutes')",
                eventBatch);

        jdbcTemplate.batchUpdate(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, status, "
                        + "next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::delivery_status, "
                        + "CASE WHEN ? THEN now() - interval '60 seconds' ELSE NULL END, "
                        + "now() - interval '5 minutes', now() - interval '5 minutes')",
                deliveryBatch);

        jdbcTemplate.execute("ANALYZE deliveries");
        jdbcTemplate.execute("ANALYZE subscriptions");
    }

    @Test
    void dueQueryUsesIdxDeliveriesDueAndNotASequentialScan() throws Exception {
        String planJson = jdbcTemplate.queryForObject(
                "EXPLAIN (FORMAT JSON) " + DUE_QUERY, String.class);

        JsonNode root = objectMapper.readTree(planJson);
        JsonNode plan = root.get(0).get("Plan");

        assertThat(usesIndexByName(plan, "idx_deliveries_due"))
                .as("plan should scan idx_deliveries_due: %s", planJson)
                .isTrue();

        assertThat(hasSeqScanOnDeliveries(plan))
                .as("plan should not sequentially scan deliveries: %s", planJson)
                .isFalse();
    }

    private boolean usesIndexByName(JsonNode node, String indexName) {
        JsonNode indexNameNode = node.get("Index Name");
        if (indexNameNode != null && indexNameNode.asText().equals(indexName)) {
            return true;
        }
        return anyChild(node, child -> usesIndexByName(child, indexName));
    }

    private boolean hasSeqScanOnDeliveries(JsonNode node) {
        JsonNode nodeType = node.get("Node Type");
        JsonNode relationName = node.get("Relation Name");
        if (nodeType != null && "Seq Scan".equals(nodeType.asText())
                && relationName != null && "deliveries".equals(relationName.asText())) {
            return true;
        }
        return anyChild(node, this::hasSeqScanOnDeliveries);
    }

    private boolean anyChild(JsonNode node, java.util.function.Predicate<JsonNode> predicate) {
        JsonNode plans = node.get("Plans");
        if (plans == null) {
            return false;
        }
        for (JsonNode child : plans) {
            if (predicate.test(child)) {
                return true;
            }
        }
        return false;
    }
}
