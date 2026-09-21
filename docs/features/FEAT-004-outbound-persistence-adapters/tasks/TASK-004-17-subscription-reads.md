---
id: TASK-004-17
feature: FEAT-004
title: "SubscriptionJdbcRepository reads: findActiveForEvent with array containment and deliverability gates, plus findById"
status: Ready for Review
agent: dba
depends_on: [TASK-004-05]
date: 2026-09-20
---

# TASK-004-17: subscription reads

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepository.java` (new; TASK-004-18 and -19 add the writes)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepositoryTest.java` (new; TASK-004-18 extends it)
- Concern: the two subscription reads.

Implements `SubscriptionRepositoryPort` (which stays a single cross-tenant interface, unsplit). `@Repository`, no `@Transactional`.

### `findActiveForEvent(String clientId, String eventType)`

The gateway's fan-out resolution, ADR-002 §1.1 step 2.

```sql
SELECT <explicit columns> FROM subscriptions
 WHERE client_id = :client_id
   AND event_types @> ARRAY[:event_type]::text[]
   AND active
   AND verification_state = 'VERIFIED'::verification_state
```

Four requirements, each load-bearing:

1. **`client_id` is a query predicate, not a post-fetch check.** ADR-002 §1.1 step 2 is explicit: tenant isolation is structural, and there must be no code path where another client's subscription is materialized in memory and then compared. Do not fetch-then-filter in Java. This is the strongest form of the A01 control in this feature.
2. **`event_types` containment uses `@>`**, so the GIN index (FEAT-002) is usable. String splitting, `LIKE`, or `= ANY` on a client-supplied value defeats the index.
3. **`active` AND `verification_state = 'VERIFIED'` are both required.** ADR-005 §3 makes deliverability the conjunction: `active` is the client's assertion, `verification_state` is the platform's proof, and they are set by different parties. An unverified subscription must never enter a fan-out (ADR-005 §2), so omitting the second predicate would deliver to an unverified endpoint.
4. `throttled_until` and `circuit_state` are **not** filtered here. This is the ingest path, which writes `PENDING` rows; the relay's due-query (TASK-004-11) applies the circuit and throttle gates at scheduling time. Filtering here would drop the event entirely rather than delaying it.

Returns a list, empty when nothing matches. ADR-002 §1.1 step 3: zero matches is a normal outcome, the event is still stored, nothing downstream happens. No exception.

### `findById(UUID subscriptionId)`

`Optional<Subscription>`, single-row read. **Cross-tenant, no `client_id` predicate**, because the worker resolves the subscription for a delivery it already holds and runs with no principal (ADR-002 §2.2 step 3). Comment it with the same reasoning `DeliveryPipelineRepositoryPort.findById` carries, citing ADR-007 §5.2's pipeline-port carve-out and Amendment E1.

### Required tests

1. `findActiveForEvent` returns matching subscriptions for the right client.
2. **A subscription of another client with the same `event_type` is not returned.** The A01 control.
3. A subscription whose `event_types` array contains the type among several is returned; one that does not is not. Cover single-element and multi-element arrays.
4. `active = false` excluded. `verification_state = 'PENDING_VERIFICATION'` excluded. Both separately, and one row failing only one of the two gates for each.
5. A subscription with `throttled_until` in the future **is** returned, and one with `circuit_state = 'OPEN'` **is** returned. Requirement 4 above, asserted positively so nobody later "fixes" it by adding the gates.
6. No match returns an empty list, never null.
7. `findById` returns the subscription regardless of tenant; absent returns `Optional.empty()`.
8. `EXPLAIN (FORMAT JSON)` on `findActiveForEvent` shows the GIN index on `event_types` used and no sequential scan, with enough seeded rows that a seq scan is not cheapest. Existing style in `src/test/java/com/cobre/challenge/schema/`.

Real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC — `@>` and GIN planning are Postgres behaviors.

## Out of Scope

- `deactivate` and `setThrottledUntil` (TASK-004-18).
- The four circuit operations (TASK-004-19).
- No split of this port. Cross-tenant throughout.
- No subscription CRUD (out of scope project-wide, Q9) and no verification handshake (ADR-005 §2).
- No secret decryption. `secret_ref` is a reference, not a secret; the adapter never resolves it (ADR-004 §2).
- No `max_concurrency` enforcement or in-flight count.
- No `TenantId`.

## Acceptance Criteria

- [ ] `SubscriptionJdbcRepository` implements `SubscriptionRepositoryPort`, `@Repository`, no `@Transactional`, no mutable state.
- [ ] `findActiveForEvent`'s `WHERE` clause contains `client_id`, `@>` containment, `active`, and `verification_state = 'VERIFIED'`. No Java-side filtering of any of the four.
- [ ] `throttled_until` and `circuit_state` do **not** appear in this query.
- [ ] `findById` has no `client_id` predicate and carries the comment justifying it with an ADR-007 E1 citation.
- [ ] Explicit column lists; every value bound; enum literals cast; the array parameter bound as an array, not a concatenated string.
- [ ] Empty results return empty collections and `Optional.empty()`, never null.
- [ ] Tests written and passing: all eight above against real Postgres via `TestcontainersConfiguration`. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.SubscriptionJdbcRepositoryTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** test 2 is the control, and requirement 1 (predicate, not post-filter) is the reason it holds structurally. **A05:** `event_type` is bound into an array parameter, never concatenated. **A09:** `secret_ref` and `target_url` are never logged.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
