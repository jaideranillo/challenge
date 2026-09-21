package com.cobre.challenge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import com.cobre.challenge.domain.model.subscription.Subscription;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Integration tests for {@link SubscriptionJdbcRepository} — reads and classification writes.
 *
 * <p>Real Postgres via {@link TestcontainersConfiguration}. No H2, no mocked JDBC.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubscriptionJdbcRepositoryTest {

    @Autowired
    SubscriptionJdbcRepository repo;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private String clientId;

    @BeforeEach
    void fixtures() {
        clientId = "client-sub-" + UUID.randomUUID();
    }

    // -----------------------------------------------------------------------
    // findActiveForEvent (TASK-004-17)
    // -----------------------------------------------------------------------

    /** Test 1: active subscription matching event type is returned. */
    @Test
    void findActiveForEvent_matchingEventType_returnsSubscription() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).contains(sid);
    }

    /** Test 2: inactive subscription is excluded even if event type matches. */
    @Test
    void findActiveForEvent_inactiveSubscription_excluded() {
        UUID sid = insertSubscription(clientId, false, new String[]{"payment.completed"});
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).doesNotContain(sid);
    }

    /** Test 3: subscription for different event type excluded (GIN containment). */
    @Test
    void findActiveForEvent_differentEventType_excluded() {
        UUID sid = insertSubscription(clientId, true, new String[]{"order.created"});
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).doesNotContain(sid);
    }

    /** Test 4: wrong client_id excluded (A01). */
    @Test
    void findActiveForEvent_foreignClientId_excluded() {
        String otherCid = "client-other-" + UUID.randomUUID();
        UUID sid = insertSubscription(otherCid, true, new String[]{"payment.completed"});
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).doesNotContain(sid);
    }

    /** Test 5: no matching subscriptions returns empty list, not null. */
    @Test
    void findActiveForEvent_noMatches_returnsEmptyList() {
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result).isNotNull().isEmpty();
    }

    /** Test 6: subscription with multiple event types matches any of them. */
    @Test
    void findActiveForEvent_multipleEventTypes_matchesAny() {
        UUID sid = insertSubscription(clientId, true,
                new String[]{"payment.completed", "order.created"});
        assertThat(repo.findActiveForEvent(clientId, "payment.completed")
                .stream().map(Subscription::subscriptionId)).contains(sid);
        assertThat(repo.findActiveForEvent(clientId, "order.created")
                .stream().map(Subscription::subscriptionId)).contains(sid);
    }

    /**
     * Test: unverified subscription excluded even though active (ADR-005 §3 conjunction).
     * Isolates the verification_state gate from the active gate.
     */
    @Test
    void findActiveForEvent_pendingVerification_excluded() {
        UUID sid = insertSubscription(clientId, true,
                new String[]{"payment.completed"}, "PENDING_VERIFICATION", null, "CLOSED");
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).doesNotContain(sid);
    }

    /**
     * Test: throttled_until in the future does not exclude a subscription from this query.
     * This is the ingest path; the relay's due-query applies the throttle gate at scheduling
     * time (requirement 4, TASK-004-17). Asserted positively so the gate is never added here.
     */
    @Test
    void findActiveForEvent_throttledUntilFuture_stillReturned() {
        Instant future = Instant.now().plusSeconds(3600);
        UUID sid = insertSubscription(clientId, true,
                new String[]{"payment.completed"}, "VERIFIED", future, "CLOSED");
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).contains(sid);
    }

    /**
     * Test: an OPEN circuit does not exclude a subscription from this query. Same reasoning
     * as the throttle test above; the relay due-query applies the circuit gate, not this one.
     */
    @Test
    void findActiveForEvent_circuitOpen_stillReturned() {
        UUID sid = insertSubscription(clientId, true,
                new String[]{"payment.completed"}, "VERIFIED", null, "OPEN");
        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).contains(sid);
    }

    // -----------------------------------------------------------------------
    // findById (TASK-004-17)
    // -----------------------------------------------------------------------

    /** Test 7: findById returns the subscription (cross-tenant). */
    @Test
    void findById_existingSubscription_returned() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        Optional<Subscription> result = repo.findById(sid);
        assertThat(result).isPresent();
        assertThat(result.get().subscriptionId()).isEqualTo(sid);
    }

    /** Test 8: findById for non-existent id returns Optional.empty(). */
    @Test
    void findById_nonExistent_returnsEmpty() {
        assertThat(repo.findById(UUID.randomUUID())).isEmpty();
    }

    /** Test 9: findById crosses tenants (no client_id predicate). */
    @Test
    void findById_crossTenant_returned() {
        String otherCid = "client-other-" + UUID.randomUUID();
        UUID sid = insertSubscription(otherCid, true, new String[]{"payment.completed"});
        // findById has no client_id filter — it's intentionally cross-tenant
        assertThat(repo.findById(sid)).isPresent();
    }

    // -----------------------------------------------------------------------
    // deactivate (TASK-004-18)
    // -----------------------------------------------------------------------

    // NOTE (verified against the committed port, TASK-004-18): SubscriptionRepositoryPort
    // declares deactivate(UUID) — one argument, no Instant — and docs/concerns.md documents
    // this as a pre-existing, deliberate database-clock exception. The port signature is not
    // changed here; these calls are the correct 1-arg form.

    /** Test: deactivate active subscription returns true, sets active=false, advances updated_at. */
    @Test
    void deactivate_activeSubscription_setsInactiveAndAdvancesUpdatedAt() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        OffsetDateTime before = readOdt(sid, "updated_at");

        boolean result = repo.deactivate(sid);

        assertThat(result).isTrue();
        assertThat(readBoolean(sid, "active")).isFalse();
        // Bound, not exact equality: updated_at comes from the database clock (docs/concerns.md).
        assertThat(readOdt(sid, "updated_at")).isAfterOrEqualTo(before);
    }

    /** Test: deactivate on an already-inactive subscription returns false and changes nothing. */
    @Test
    void deactivate_alreadyInactive_returnsFalseAndUnchanged() {
        UUID sid = insertSubscription(clientId, false, new String[]{"payment.completed"});
        OffsetDateTime before = readOdt(sid, "updated_at");

        boolean result = repo.deactivate(sid);

        assertThat(result).isFalse();
        assertThat(readBoolean(sid, "active")).isFalse();
        assertThat(readOdt(sid, "updated_at")).isEqualTo(before);
    }

    /** Test: deactivate leaves verification_state, verified_at and every circuit column untouched. */
    @Test
    void deactivate_leavesVerificationAndCircuitColumnsUntouched() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        jdbc.update(
                "UPDATE subscriptions SET verified_at = now(), circuit_state = 'OPEN'::circuit_state,"
                        + " circuit_opened_at = now(), circuit_backoff = interval '30 seconds',"
                        + " consecutive_opens = 2 WHERE subscription_id = :id",
                new MapSqlParameterSource("id", sid));
        OffsetDateTime verifiedAtBefore = readOdt(sid, "verified_at");
        OffsetDateTime circuitOpenedAtBefore = readOdt(sid, "circuit_opened_at");

        repo.deactivate(sid);

        assertThat(readText(sid, "verification_state")).isEqualTo("VERIFIED");
        assertThat(readOdt(sid, "verified_at")).isEqualTo(verifiedAtBefore);
        assertThat(readText(sid, "circuit_state")).isEqualTo("OPEN");
        assertThat(readOdt(sid, "circuit_opened_at")).isEqualTo(circuitOpenedAtBefore);
        assertThat(readInt(sid, "consecutive_opens")).isEqualTo(2);
    }

    /** Test: deactivate on an unknown id returns false without throwing. */
    @Test
    void deactivate_nonExistentId_returnsFalse() {
        assertThat(repo.deactivate(UUID.randomUUID())).isFalse();
    }

    /** Test: a deactivated subscription is no longer returned by findActiveForEvent. */
    @Test
    void deactivate_thenFindActiveForEvent_excludesSubscription() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});

        repo.deactivate(sid);

        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).doesNotContain(sid);
    }

    // -----------------------------------------------------------------------
    // setThrottledUntil (TASK-004-18)
    // -----------------------------------------------------------------------

    /** Test: setThrottledUntil sets the value and returns true. */
    @Test
    void setThrottledUntil_setsColumnAndReturnsTrue() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        Instant until = Instant.now().plus(60, ChronoUnit.SECONDS);

        boolean result = repo.setThrottledUntil(sid, until);

        assertThat(result).isTrue();
        OffsetDateTime stored = readOdt(sid, "throttled_until");
        assertThat(stored).isNotNull();
        assertThat(stored.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(until.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Test: setThrottledUntil uses GREATEST — a later call cannot move it back.
     *
     * <p>Proves the monotonic update constraint (GREATEST chosen and documented in the adapter,
     * per TASK-004-18). If a future change switches to last-writer-wins, this test must be
     * rewritten to assert the earlier value wins instead — it is required to match the comment.
     */
    @Test
    void setThrottledUntil_greatestMonotonic_cannotMoveBack() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        Instant later = Instant.now().plus(120, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);
        Instant earlier = Instant.now().plus(30, ChronoUnit.SECONDS);

        repo.setThrottledUntil(sid, later);
        repo.setThrottledUntil(sid, earlier); // attempt to move back
        OffsetDateTime stored = readOdt(sid, "throttled_until");
        assertThat(stored.toInstant().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(later.truncatedTo(ChronoUnit.SECONDS));
    }

    /** Test: setThrottledUntil does not change circuit_state or any other circuit column. */
    @Test
    void setThrottledUntil_leavesCircuitColumnsUntouched() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});
        jdbc.update(
                "UPDATE subscriptions SET circuit_state = 'OPEN'::circuit_state,"
                        + " circuit_opened_at = now(), circuit_backoff = interval '30 seconds',"
                        + " consecutive_opens = 3 WHERE subscription_id = :id",
                new MapSqlParameterSource("id", sid));
        OffsetDateTime circuitOpenedAtBefore = readOdt(sid, "circuit_opened_at");

        repo.setThrottledUntil(sid, Instant.now().plusSeconds(60));

        assertThat(readText(sid, "circuit_state")).isEqualTo("OPEN");
        assertThat(readOdt(sid, "circuit_opened_at")).isEqualTo(circuitOpenedAtBefore);
        assertThat(readInt(sid, "consecutive_opens")).isEqualTo(3);
    }

    /** Test: a throttled subscription is still returned by findActiveForEvent (relay's gate, not ingest's). */
    @Test
    void setThrottledUntil_thenFindActiveForEvent_stillReturnsSubscription() {
        UUID sid = insertSubscription(clientId, true, new String[]{"payment.completed"});

        repo.setThrottledUntil(sid, Instant.now().plusSeconds(3600));

        List<Subscription> result = repo.findActiveForEvent(clientId, "payment.completed");
        assertThat(result.stream().map(Subscription::subscriptionId)).contains(sid);
    }

    /** Test: setThrottledUntil on an unknown id returns false without throwing. */
    @Test
    void setThrottledUntil_nonExistentId_returnsFalse() {
        assertThat(repo.setThrottledUntil(UUID.randomUUID(), Instant.now().plusSeconds(60))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Default fixture: verified, no throttle, closed circuit — matches on all gates. */
    private UUID insertSubscription(String cid, boolean active, String[] eventTypes) {
        return insertSubscription(cid, active, eventTypes, "VERIFIED", null, "CLOSED");
    }

    private UUID insertSubscription(
            String cid,
            boolean active,
            String[] eventTypes,
            String verificationState,
            Instant throttledUntil,
            String circuitState) {
        UUID sid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscriptions (subscription_id, client_id, target_url, secret_ref, "
                        + "event_types, active, verification_state, throttled_until, circuit_state) "
                        + "VALUES (:id, :cid, 'https://example.com/hook', 'ref', :et::text[], :active, "
                        + ":vs::verification_state, :tu, :cs::circuit_state)",
                new MapSqlParameterSource()
                        .addValue("id", sid)
                        .addValue("cid", cid)
                        .addValue("et", toArray(eventTypes))
                        .addValue("active", active)
                        .addValue("vs", verificationState)
                        .addValue("tu", throttledUntil == null
                                ? null
                                : OffsetDateTime.ofInstant(throttledUntil, ZoneOffset.UTC))
                        .addValue("cs", circuitState));
        return sid;
    }

    private Boolean readBoolean(UUID id, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), Boolean.class);
    }

    private OffsetDateTime readOdt(UUID id, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getObject(col, OffsetDateTime.class));
    }

    private String readText(UUID id, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + "::text FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private Integer readInt(UUID id, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM subscriptions WHERE subscription_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
    }

    private String toArray(String[] values) {
        return "{" + String.join(",", values) + "}";
    }
}
