package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Integration tests for {@link DeliveryQueryJdbcRepository#findById}.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryQueryJdbcRepositoryTest {

    @Autowired
    DeliveryQueryJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;
    private UUID subscriptionId;

    @BeforeEach
    void fixtures() {
        clientId = "client-query-" + UUID.randomUUID();
        subscriptionId = insertSubscription(clientId);
    }

    /** Test 1: own-tenant row returns the delivery with all components mapped. */
    @Test
    void findById_ownTenant_returnsDelivery() {
        UUID deliveryId = insertDelivery(clientId, subscriptionId);
        Optional<Delivery> result = repo.findById(deliveryId, new TenantId(clientId));
        assertThat(result).isPresent();
        assertThat(result.get().deliveryId()).isEqualTo(deliveryId);
        assertThat(result.get().clientId()).isEqualTo(clientId);
        assertThat(result.get().eventCreatedAt()).isNotNull();
    }

    /**
     * Test 2: another client's row returns Optional.empty() (A01 control).
     *
     * <p>ADR-005 §1 maps this to 404 rather than 403 so the endpoint does not leak the existence
     * of another tenant's ids. The adapter must return empty, not throw.
     */
    @Test
    void findById_foreignTenant_returnsEmpty_a01Control() {
        String otherClientId = "client-other-" + UUID.randomUUID();
        UUID otherSub = insertSubscription(otherClientId);
        UUID deliveryId = insertDelivery(otherClientId, otherSub);

        // Query with wrong client_id
        Optional<Delivery> result = repo.findById(deliveryId, new TenantId(clientId));
        assertThat(result).isEmpty();
    }

    /** Test 3: non-existent id returns Optional.empty(). */
    @Test
    void findById_nonExistentId_returnsEmpty() {
        assertThat(repo.findById(UUID.randomUUID(), new TenantId(clientId))).isEmpty();
    }

    /**
     * Test 4: wrong-tenant and non-existent return identically (both empty, no exception).
     *
     * <p>This is what lets ADR-005 §1 return 404 rather than 403 — both paths are
     * indistinguishable at the adapter boundary.
     */
    @Test
    void findById_foreignTenant_andNonExistent_areBothEmpty_noException() {
        String otherClientId = "client-other2-" + UUID.randomUUID();
        UUID otherSub = insertSubscription(otherClientId);
        UUID foreignId = insertDelivery(otherClientId, otherSub);
        UUID nonExistentId = UUID.randomUUID();

        Optional<Delivery> wrongTenant = repo.findById(foreignId, new TenantId(clientId));
        Optional<Delivery> nonExistent = repo.findById(nonExistentId, new TenantId(clientId));

        assertThat(wrongTenant).isEmpty();
        assertThat(nonExistent).isEmpty();
    }

    /**
     * Test 5: client_id with SQL metacharacters returns empty (A05 bound-parameter proof).
     */
    @Test
    void findById_sqlInjectionAttemptInClientId_returnsEmpty_a05() {
        UUID id = insertDelivery(clientId, subscriptionId);
        Optional<Delivery> result = repo.findById(id, new TenantId("' OR 1=1 --"));
        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertDelivery(String cid, UUID sid) {
        String eventId = "EVT-QRY-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_events (event_id, client_id, event_type, content, created_at) "
                        + "VALUES (:id, :cid, 'payment.completed', 'test', now())",
                new MapSqlParameterSource().addValue("id", eventId).addValue("cid", cid));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO deliveries (delivery_id, event_id, subscription_id, client_id, event_created_at) "
                        + "VALUES (:id, :eid, :sid, :cid, now())",
                new MapSqlParameterSource()
                        .addValue("id", deliveryId).addValue("eid", eventId)
                        .addValue("sid", sid).addValue("cid", cid));
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
}
