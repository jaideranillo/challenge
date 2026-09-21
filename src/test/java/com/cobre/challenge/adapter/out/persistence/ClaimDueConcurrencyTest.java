package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency tests for {@link DeliveryPipelineJdbcRepository#claimDue}.
 *
 * <p>Each test uses two genuinely concurrent open transactions on separate threads (via
 * {@link TransactionTemplate}) rather than two sequential calls. A Spring-managed
 * {@code @Transactional} test method would serialize both claims on one connection and
 * never exercise {@code SKIP LOCKED}.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClaimDueConcurrencyTest {

    @Autowired
    DeliveryPipelineJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private String clientId;
    private UUID subscriptionId;
    private Instant asOf;

    @BeforeEach
    void perTestFixtures() {
        // Same isolation requirement as ClaimDuePredicateTest: claimDue is a global,
        // non-tenant-scoped LIMIT query, so a still-due row left by a prior test would
        // sort ahead of this test's fixture and crowd it out of a fixed-size batch.
        jdbc.getJdbcTemplate().update("DELETE FROM delivery_attempts");
        jdbc.getJdbcTemplate().update("DELETE FROM deliveries");
        clientId = "client-conc-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
        asOf = Instant.now().plus(5, ChronoUnit.MINUTES); // all seeded rows are already due
    }

    /**
     * Tests 1-4: two concurrent transactions claim disjoint batches.
     *
     * <ul>
     *   <li>Test 1: the two id sets are disjoint.
     *   <li>Test 2: neither call blocks the other (timeout-bounded).
     *   <li>Test 3: every returned row is QUEUED with next_attempt_at pushed; no row appears twice.
     *   <li>Test 4: a third sequential call after both commit returns the remaining rows.
     * </ul>
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void claimDue_concurrentTransactions_claimDisjointBatches() throws Exception {
        int total = 20;
        int batchSize = 8;
        seedDueDeliveries(total);

        List<UUID>[] claims = new List[2];
        AtomicReference<Throwable> t1Err = new AtomicReference<>();
        AtomicReference<Throwable> t2Err = new AtomicReference<>();
        CountDownLatch t1HasClaimed = new CountDownLatch(1);
        CountDownLatch t2HasClaimed = new CountDownLatch(1);

        TransactionTemplate tx = new TransactionTemplate(txManager);

        Thread t1 = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    claims[0] = repo.claimDue(batchSize, asOf).stream()
                            .map(Delivery::deliveryId).collect(java.util.stream.Collectors.toList());
                    t1HasClaimed.countDown();
                    try {
                        t2HasClaimed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        status.setRollbackOnly();
                    }
                });
            } catch (Throwable e) {
                t1Err.set(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                // Wait for T1 to have its rows locked before starting
                t1HasClaimed.await(10, TimeUnit.SECONDS);
                tx.executeWithoutResult(status -> {
                    claims[1] = repo.claimDue(batchSize, asOf).stream()
                            .map(Delivery::deliveryId).collect(java.util.stream.Collectors.toList());
                    t2HasClaimed.countDown();
                });
            } catch (Throwable e) {
                t2Err.set(e);
            }
        });

        t1.start();
        t2.start();
        t1.join(12_000);
        t2.join(12_000);

        assertThat(t1Err.get()).as("T1 error").isNull();
        assertThat(t2Err.get()).as("T2 error").isNull();
        assertThat(claims[0]).as("T1 claims").isNotEmpty();
        assertThat(claims[1]).as("T2 claims").isNotEmpty();

        // Test 1: disjoint
        Set<UUID> union = new HashSet<>(claims[0]);
        Set<UUID> intersection = new HashSet<>(claims[0]);
        intersection.retainAll(new HashSet<>(claims[1]));
        assertThat(intersection).as("id sets must be disjoint").isEmpty();

        // Test 2: neither blocked (already verified by @Timeout)

        // Test 3: all returned rows are now QUEUED
        union.addAll(claims[1]);
        for (UUID id : union) {
            assertThat(readStatus(id)).isEqualTo("QUEUED");
        }

        // Test 4: third sequential call returns remaining rows (total - batchSize*2 claimed)
        List<UUID> remaining = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            repo.claimDue(total, asOf).forEach(d -> remaining.add(d.deliveryId()));
        });
        assertThat(remaining).doesNotContainAnyElementsOf(union);
    }

    /**
     * Test 5: {@code FOR UPDATE OF d} specifically.
     *
     * <p>Seed two due deliveries sharing one subscription_id. Two concurrent claimDue(1)
     * calls must each return one delivery. Under a bare {@code FOR UPDATE ... SKIP LOCKED}
     * the second call would skip because the shared subscriptions row is locked; with
     * {@code OF d} only the delivery row is locked and both claims succeed.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void claimDue_forUpdateOfD_twoDeliveriesSameSubscription_bothClaimed() throws Exception {
        // Two deliveries for the same subscription
        seedDueDeliveriesForSubscription(subscriptionId, 2);

        List<UUID>[] claims = new List[2];
        AtomicReference<Throwable> t1Err = new AtomicReference<>();
        AtomicReference<Throwable> t2Err = new AtomicReference<>();
        CountDownLatch t1HasClaimed = new CountDownLatch(1);
        CountDownLatch t2HasClaimed = new CountDownLatch(1);

        TransactionTemplate tx = new TransactionTemplate(txManager);

        Thread t1 = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    claims[0] = repo.claimDue(1, asOf).stream()
                            .map(Delivery::deliveryId).collect(java.util.stream.Collectors.toList());
                    t1HasClaimed.countDown();
                    try {
                        t2HasClaimed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        status.setRollbackOnly();
                    }
                });
            } catch (Throwable e) {
                t1Err.set(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                t1HasClaimed.await(10, TimeUnit.SECONDS);
                tx.executeWithoutResult(status -> {
                    claims[1] = repo.claimDue(1, asOf).stream()
                            .map(Delivery::deliveryId).collect(java.util.stream.Collectors.toList());
                    t2HasClaimed.countDown();
                });
            } catch (Throwable e) {
                t2Err.set(e);
            }
        });

        t1.start();
        t2.start();
        t1.join(12_000);
        t2.join(12_000);

        assertThat(t1Err.get()).isNull();
        assertThat(t2Err.get()).isNull();
        // Both must have claimed exactly one delivery (proves OF d)
        assertThat(claims[0]).hasSize(1);
        assertThat(claims[1]).hasSize(1);
        // They must be different rows
        assertThat(claims[0].get(0)).isNotEqualTo(claims[1].get(0));
    }

    /**
     * Test 6: calling claimDue outside a transaction throws IllegalStateException
     * and no row is modified (fail-closed, A10).
     */
    @Test
    void claimDue_noTransaction_throwsIllegalStateException_noRowModified() {
        UUID id = seedOneDueDelivery();
        String statusBefore = readStatus(id);

        assertThatThrownBy(() -> repo.claimDue(10, asOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        assertThat(readStatus(id)).isEqualTo(statusBefore);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void seedDueDeliveries(int count) {
        for (int i = 0; i < count; i++) {
            seedOneDueDelivery();
        }
    }

    private void seedDueDeliveriesForSubscription(UUID sid, int count) {
        for (int i = 0; i < count; i++) {
            seedDueDeliveryForSub(sid);
        }
    }

    private UUID seedOneDueDelivery() {
        return seedDueDeliveryForSub(subscriptionId);
    }

    private UUID seedDueDeliveryForSub(UUID sid) {
        String eventId = "EVT-CONC-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now() - interval '5 minutes')",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", clientId));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, "
                        + "status, next_attempt_at, created_at, updated_at, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, 'PENDING'::delivery_status, "
                        + "now() - interval '1 minute', now() - interval '5 minutes', "
                        + "now() - interval '5 minutes', now() - interval '5 minutes')",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", clientId));
        return deliveryId;
    }

    private UUID insertSubscription(String cid) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, event_types) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', ARRAY['payment.completed']::text[])",
                new MapSqlParameterSource().addValue("id", sid).addValue("cid", cid));
        return sid;
    }

    private String readStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE delivery_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }
}
