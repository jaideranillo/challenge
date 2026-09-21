package com.cobre.challenge.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
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
 * TASK-004-17 / ADR-002 §1.1 step 2: the ingest-time fan-out lookup
 * ({@code SubscriptionJdbcRepository.findActiveForEvent}) must use the GIN index
 * {@code idx_subscriptions_event_types} for the {@code event_types @> ARRAY[...]::text[]}
 * predicate, not a sequential scan of {@code subscriptions}.
 *
 * <p>Seed size: 100,000 subscriptions across 2 clients (50,000 rows each), each with 1-3 event
 * types drawn from a pool of 15, so client_id alone is a weak predicate (~50% of the table) and
 * the event_types containment test is the one that meaningfully narrows the result - so the
 * planner combines the GIN index with the client_id/active b-tree (BitmapAnd) instead of
 * fetching tens of thousands of heap tuples and filtering in memory. Smaller/less-skewed seeds
 * were tried first and each let the planner correctly skip the GIN index: 2,000 rows across 20
 * clients (table cheap enough for a seq scan), 100,000 rows across 20 clients (client_id alone
 * narrows to ~5,000 rows, cheap enough to filter without the GIN index), and 100,000 rows across
 * 4 clients (client_id narrows to ~25,000 rows, still cheaper to filter than to also scan the
 * GIN index). None of those are bugs - the planner was picking the genuinely cheaper plan for
 * that data shape. The seed is set-based (INSERT ... SELECT FROM generate_series, not a Java
 * batch loop) to keep a 100k-row seed fast. ANALYZE runs before EXPLAIN; enable_seqscan is never
 * touched — the question is whether the planner chooses the index, not whether it can.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(Lifecycle.PER_CLASS)
class SubscriptionEventTypesIndexTest {

    private static final int SUBSCRIPTION_COUNT = 100_000;
    private static final int CLIENT_COUNT = 2;
    private static final String[] EVENT_TYPE_POOL = {
        "payment.completed", "payment.failed", "order.created", "order.cancelled",
        "order.shipped", "invoice.created", "invoice.paid", "refund.issued",
        "chargeback.opened", "account.created", "account.suspended", "kyc.approved",
        "kyc.rejected", "webhook.test", "transfer.completed"
    };
    private static final String TARGET_EVENT_TYPE = "payment.completed";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seedRealisticTable() {
        String poolLiteral =
                "ARRAY['" + String.join("','", EVENT_TYPE_POOL) + "']::text[]";
        int poolSize = EVENT_TYPE_POOL.length;

        // Set-based seed (INSERT ... SELECT FROM generate_series), not a Java batch loop:
        // at 100,000 rows a client-side batch is slow to build and send, while this runs
        // entirely inside Postgres. Only 2 clients (50,000 rows each): with client_id/active
        // alone already narrowing the table a lot (tried 20 clients, then 4), the b-tree
        // idx_subscriptions_client_active is cheap enough on its own that the planner correctly
        // skips the GIN index - it has nothing left to usefully narrow. Widening each client's
        // slice to 50,000 rows (~50% of the table) makes the event_types containment test the
        // one that actually narrows the result, so the planner combines both indexes (BitmapAnd)
        // instead of fetching tens of thousands of heap tuples and filtering in memory. 1-3
        // distinct event types per row, starting at pool[(i + j) % poolSize].
        String sql =
                "WITH pool(types) AS (VALUES (" + poolLiteral + ")) "
                        + "INSERT INTO subscriptions (subscription_id, client_id, target_url, "
                        + "secret_ref, event_types, active, verification_state) "
                        + "SELECT gen_random_uuid(), "
                        + "       'client-idx-' || (i % " + CLIENT_COUNT + "), "
                        + "       'https://example.com/webhook', "
                        + "       'secret-ref', "
                        + "       (SELECT array_agg(DISTINCT pool.types[((i + j) % " + poolSize + ") + 1]) "
                        + "          FROM generate_series(0, i % 3) AS j, pool), "
                        + "       true, "
                        + "       'VERIFIED'::verification_state "
                        + "  FROM generate_series(0, ?) AS i, pool";

        jdbcTemplate.update(sql, SUBSCRIPTION_COUNT - 1);

        jdbcTemplate.execute("ANALYZE subscriptions");
    }

    @Test
    void findActiveForEventUsesGinIndexAndNotASequentialScan() throws Exception {
        String sql =
                "EXPLAIN (FORMAT JSON) SELECT subscription_id, client_id, target_url, secret_ref, "
                        + "previous_secret_ref, previous_secret_expires_at, event_types, active, "
                        + "verification_state, max_concurrency, circuit_state, throttled_until "
                        + "FROM subscriptions "
                        + "WHERE client_id = 'client-idx-0' "
                        + "  AND event_types @> ARRAY['" + TARGET_EVENT_TYPE + "']::text[] "
                        + "  AND active "
                        + "  AND verification_state = 'VERIFIED'::verification_state";

        String planJson = jdbcTemplate.queryForObject(sql, String.class);

        JsonNode root = objectMapper.readTree(planJson);
        JsonNode plan = root.get(0).get("Plan");

        assertThat(usesIndexByName(plan, "idx_subscriptions_event_types"))
                .as("plan should scan idx_subscriptions_event_types: %s", planJson)
                .isTrue();

        assertThat(hasSeqScanOnSubscriptions(plan))
                .as("plan should not sequentially scan subscriptions: %s", planJson)
                .isFalse();
    }

    private boolean usesIndexByName(JsonNode node, String indexName) {
        JsonNode indexNameNode = node.get("Index Name");
        if (indexNameNode != null && indexNameNode.asText().equals(indexName)) {
            return true;
        }
        return anyChild(node, child -> usesIndexByName(child, indexName));
    }

    private boolean hasSeqScanOnSubscriptions(JsonNode node) {
        JsonNode nodeType = node.get("Node Type");
        JsonNode relationName = node.get("Relation Name");
        if (nodeType != null && "Seq Scan".equals(nodeType.asText())
                && relationName != null && "subscriptions".equals(relationName.asText())) {
            return true;
        }
        return anyChild(node, this::hasSeqScanOnSubscriptions);
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
