---
id: TASK-004-08
feature: FEAT-004
title: "Testcontainers: insert round-trip, event_created_at fidelity, and a second conditional claim affecting zero rows"
status: Not Started
agent: dba
depends_on: [TASK-004-07]
date: 2026-09-20
---

# TASK-004-08: tests for `insert`, `findById` and `claimForProcessing`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepositoryTest.java` (new)
- Concern: the insert/load round trip and the conditional claim's zero-row property.

`@SpringBootTest` picking up `TestcontainersConfiguration`'s `@ServiceConnection` Postgres. **Real Postgres. No H2, no mocked `JdbcTemplate`, no mocked `ResultSet`** — every property under test is a Postgres behavior.

### Required tests

**`insert`**

1. Round trip: insert a `Delivery`, `findById` it, assert every component equal, including both new ones.
2. **`event_created_at` fidelity — the test this feature exists for.** Insert a delivery whose `eventCreatedAt` is deliberately far from wall-clock now (say 30 days earlier, the shape of a replay). Assert the persisted `event_created_at` equals the aggregate's value and that `created_at` does **not** equal it. A test where the two coincide proves nothing.
3. `trace_context`: one row with a traceparent, one with `Optional.empty()`; assert both round-trip, the second as `Optional.empty()` and not as an empty string.
4. Idempotency guard: two inserts violating ADR-003 §2's partial unique index; assert a `DuplicateKeyException` (or its Spring subtype) **propagates** out of the adapter. If it is swallowed, a double replay looks like a success.

**`findById`**

5. Absent row returns `Optional.empty()`, not null and not an exception.
6. A row belonging to a different `client_id` **is** returned. This is the cross-tenant pipeline read working as designed (ADR-007 E1); asserting it explicitly stops a later reviewer from "fixing" it by adding a tenant predicate, and documents that the isolation guarantee lives on the query port, tested in TASK-004-13.

**`claimForProcessing` — the directive's second stated property**

7. `QUEUED` row: returns `true`, status becomes `PROCESSING`, `updated_at` advances.
8. **A second claim on the already-claimed row returns `false` and affects zero rows**, and the row's `updated_at` is unchanged by the failed attempt. This is the double-send guard.
9. Each of `PENDING`, `RETRYING`, `PROCESSING`, `DELIVERED`, `DEAD`, `FAILED` returns `false` and mutates nothing. A parameterized test over `DeliveryStatus` minus `QUEUED`.
10. A non-existent `delivery_id` returns `false` without throwing.
11. **Nothing else moves:** after a successful claim, assert `attempt_count`, `next_attempt_at`, `last_error`, `delivered_at` and `event_created_at` are all byte-identical to their pre-claim values.

Test 8 is the acceptance property the user stated for this feature ("a second conditional claim on an already-claimed row affects zero rows") and must assert the returned `boolean`, not only the row state.

### Conventions

Read the assertion style and fixture style from FEAT-002's existing schema tests before writing (`src/test/java/com/cobre/challenge/schema/`). Insert fixtures with plain SQL where the adapter under test is not the thing being exercised, so a bug in `insert` cannot silently pass a `claimForProcessing` test.

## Out of Scope

- Concurrency. TASK-004-12 owns the two-transaction disjoint-batch test; this task's claim tests are sequential.
- The four outcome writes and `deferDelivery` (TASK-004-09, -10 own their own tests).
- `claimDue` (TASK-004-11, -12).
- Any query-port method (TASK-004-13, -15).
- No `EXPLAIN` assertion. Single-row PK updates; index-plan tests belong to TASK-004-12 and -15.
- No new test container, no LocalStack. Postgres only; nothing here touches SQS.

## Acceptance Criteria

- [ ] All eleven tests above exist and pass against real Postgres via `TestcontainersConfiguration`.
- [ ] Test 2 uses an `eventCreatedAt` deliberately distant from insert time and asserts `event_created_at != created_at`.
- [ ] Test 4 asserts the duplicate-key exception propagates out of the adapter.
- [ ] Test 6 asserts the cross-tenant read **succeeds**, with a comment citing ADR-007 E1.
- [ ] Test 8 asserts the `false` return value, not only the persisted state.
- [ ] Test 9 is parameterized over every `DeliveryStatus` except `QUEUED`.
- [ ] Test 11 asserts five specific columns unchanged after a successful claim.
- [ ] No H2, no mocked JDBC or `ResultSet` anywhere in the file.
- [ ] Fixtures for the claim tests are inserted with plain SQL, not through the adapter's own `insert`.
- [ ] Tests written and passing: `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeliveryPipelineJdbcRepositoryTest"` green with Docker running, and the full suite still green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. Test 6 documents an intentional cross-tenant read; **A01** isolation for the client-facing path is TASK-004-13's. No real client payload or secret in any fixture (**A09**).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
