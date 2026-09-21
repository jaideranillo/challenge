---
id: TASK-002-05
feature: FEAT-002
title: "Testcontainers-Postgres tests: idempotency invariant and due-query index usage"
status: Ready for Review
agent: dba
depends_on: [TASK-002-04]
date: 2026-09-20
---

# TASK-002-05: Testcontainers-Postgres tests — idempotency invariant and due-query index usage

> **Scope adjusted: partitioning deferred.** The core intent is unchanged — (a) the partial unique index rejects a second live row, (b) it accepts one once the first is terminal, (c) the relay's due-query uses `idx_deliveries_due`. What changes is that `deliveries` is no longer partitioned (ADR-003 §3), so every partition-specific accommodation below is gone: no per-partition index names to resolve via `pg_inherits`, no `Merge Append` across partitions, no empty-`DEFAULT`-partition sequential scan to tolerate, and the invariant is now a plain partial unique index on `deliveries` rather than a side table plus trigger. The existing two test classes were written against the partitioned schema and must be **revised**, not merely re-run — in particular the (c) assertions that resolve child index names and that special-case an empty partition's `Seq Scan`. Status reset to `Not Started` for that reason.

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

Rationale for the role: these tests assert **constraint and index behavior in PostgreSQL**, not application logic. There is no domain model, no use case and no port to test; the subject is the schema TASK-002-02/03/04 produced. That is query/schema verification, which CLAUDE.md's roster puts with `dba`, not `backend-engineer`.

## Scope

Three assertions, each of which protects a design property that would otherwise fail silently in production.

- File(s):
  - `src/test/java/com/cobre/challenge/schema/DeliveryIdempotencyIndexTest.java` (new) — assertions (a) and (b)
  - `src/test/java/com/cobre/challenge/schema/DeliveryDueQueryIndexTest.java` (new) — assertion (c)
- Concern: schema invariants under a real PostgreSQL. Nothing else.

Two files rather than one because they are two different kinds of test — a constraint test and a planner test — with different setup costs and different reasons to fail. Splitting them means a planner regression and a constraint regression are distinguishable at a glance in the test report. If your setup helper grows large enough to want a third file, that is acceptable; do not exceed it.

### Environment

**Testcontainers PostgreSQL, never H2, never the compose stack** (CLAUDE.md). `TestcontainersConfiguration` (`src/test/java/com/cobre/challenge/TestcontainersConfiguration.java`) already declares a `@ServiceConnection` `PostgreSQLContainer`; reuse it via `@Import(TestcontainersConfiguration.class)` rather than starting a container of your own. Flyway (TASK-002-01) applies V1-V3 to that container on context startup, so the schema under test is the real migrated schema, not a test fixture.

Use `NamedParameterJdbcTemplate` or plain `JdbcTemplate` for setup and assertions. **Do not introduce a Spring Data JDBC repository, an entity, or any `domain`/`application` type** — none exist yet and creating one here would put an adapter ahead of its port, which is backwards for the hexagon and out of this feature's scope.

Each test must insert its own `notification_events` and `subscriptions` rows (FK parents) and clean up after itself, or use `@Transactional` rollback — your choice, but tests must not depend on each other's rows or on ordering.

### (a) The partial unique index rejects a second live row

Insert a `deliveries` row for a `(event_id, subscription_id)` pair in a **non-terminal** status. Insert a second row for the same pair, also non-terminal. The second insert must fail with a constraint violation.

- Assert the failure is a **unique/constraint violation** (Spring's `DuplicateKeyException` / `DataIntegrityViolationException`, or the underlying SQLState `23505`), not merely "some exception". A test that passes because of a null-constraint violation on a column you forgot to populate is a false green, and this one is easy to write by accident.
- Exercise more than one non-terminal status, not just `PENDING`. The filter is `status NOT IN ('DELIVERED','DEAD','FAILED')`, so `QUEUED`, `PROCESSING` and `RETRYING` are all live and all must be rejected. A parameterized test over the four live statuses is the natural shape.
- The mechanism is now a plain partial unique index directly on `deliveries` (TASK-002-03), so the violation surfaces as an ordinary unique-index `23505` on that index. The assertion stays on the **invariant** — behavior, not the index's name — but there is no side table and no trigger in the picture any more, and a test that still reaches for `deliveries_live_index` is asserting against a table that no longer exists.

### (b) A second row is allowed once the first is terminal

For each terminal status in turn — `DELIVERED`, `DEAD`, `FAILED` — take a live row for a pair, move it to that terminal status, then insert a second row for the same pair. The second insert must succeed.

This is what makes `POST /replay` possible at all (ADR-005 §1: replay inserts a new row rather than mutating the `DEAD` one). All three terminal statuses must be covered: `DELIVERED` because a genuine re-ingest after success must be allowed to create a new delivery, `DEAD` because that is the replay path, `FAILED` because that is the internal-recovery path (ADR-003 §1.1).

Also assert the pair of (a) and (b) together at least once in sequence — live row, second insert rejected, first row moved to terminal, second insert now accepted — since it is the transition that matters, and a schema could pass (a) and (b) independently while getting the filter's boundary wrong.

### (c) The relay's due-query uses `idx_deliveries_due`, not a sequential scan

Run `EXPLAIN` on the relay's due-query predicate from **ADR-002 §2.1** and assert the plan uses `idx_deliveries_due`.

The index under test (ADR-002 §2.1, which supersedes ADR-003 §3's narrower version):

```
CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at)
  WHERE status IN ('PENDING', 'RETRYING', 'QUEUED', 'PROCESSING');
```

The query to explain is ADR-002 §2.1's, including its `ORDER BY d.next_attempt_at` and its join to `subscriptions` — a predicate that is not the real one proves nothing about the real one. At minimum the `deliveries`-side predicate must be `status IN ('PENDING','RETRYING','QUEUED','PROCESSING') AND next_attempt_at <= now()` with `ORDER BY next_attempt_at`.

Practical notes, because a naive version of this test is worthless:

- **Seed enough rows that a sequential scan is not simply cheaper.** On a table with 20 rows PostgreSQL will correctly choose a seq scan and the test will fail for a reason that is not a bug. Seed a few thousand `deliveries` rows across a realistic status mix (mostly terminal — that is the point of the partial index, per ADR-002 §2.1's "the table may hold tens of millions of rows while the index holds only what is outstanding") and `ANALYZE` the table before explaining. State the row count you chose.
- **Do not force the plan** with `enable_seqscan = off`. That makes the test assert that the index is *usable*, which is not the question; the question is whether the planner *chooses* it for the real query at a realistic shape.
- Assert on the plan text containing `idx_deliveries_due` **and** not containing a sequential scan of `deliveries`. Using `EXPLAIN (FORMAT JSON)` and inspecting node types is more robust than substring-matching English plan output; either is acceptable if the assertion is specific.
- `deliveries` is a single unpartitioned table, so the plan has no per-partition nodes, no `Merge Append`, and no auto-generated child index names. The index node names `idx_deliveries_due` directly — assert on that name, and **remove** the `pg_inherits` child-index-name resolution and the "tolerate a `Seq Scan` on an empty partition" carve-out the previous version of this test needed. With nothing empty in the plan, a `Seq Scan` on `deliveries` is now unambiguously a failure.
- If this test proves flaky across environments, the correct response is a clearer assertion or a larger seed, **not** deleting the test or forcing the planner. A flaky planner test that gets silently disabled is worse than none, so say so in the completion note if you hit this.

## Out of Scope

- Any change to `V1`/`V2`/`V3`. If a test reveals the schema is wrong, that is a finding to report and a new migration by the owning task, not an edit to an applied migration and not a weakened assertion.
- Any `domain`, `application`, `port`, or `adapter/out/persistence` Java type. No repository, no entity, no mapper.
- `TestcontainersConfiguration` — import it, do not modify it.
- `build.gradle`, `application.yaml`, `application-local.yaml`, `compose.yaml`.
- Testing the relay, the worker, the retry policy, the circuit breaker or the bulkhead. None of that exists; the only subject here is the schema.
- Performance benchmarking. (c) asserts a **plan shape**, not a latency number. Do not add timing assertions.
- LocalStack/SQS. Nothing in this task touches the queue.

## Acceptance Criteria

- [ ] Both test classes run against Testcontainers PostgreSQL via `@Import(TestcontainersConfiguration.class)`; no H2, no in-memory database, no compose stack
- [ ] (a) A second **non-terminal** row for an existing live `(event_id, subscription_id)` pair is rejected, asserted as a constraint violation (SQLState `23505` / `DuplicateKeyException`), and covered for `PENDING`, `QUEUED`, `PROCESSING` and `RETRYING`
- [ ] (b) A second row for the same pair **succeeds** once the first row is `DELIVERED`, and again for `DEAD`, and again for `FAILED`
- [ ] At least one test walks the full transition in sequence: reject while live, accept after terminal
- [ ] (c) `EXPLAIN` on ADR-002 §2.1's due-query shows `idx_deliveries_due` in use **by that name** and no sequential scan of `deliveries`, on a seeded table of realistic size with statistics analyzed, **without** disabling `enable_seqscan`
- [ ] No test references `deliveries_live_index`, `trg_deliveries_live_index`, `pg_inherits` child-index resolution, or any partition name
- [ ] The seed size and status mix used for (c) are recorded in the completion note
- [ ] Tests are independent: any single test class or method passes when run alone (`./gradlew test --tests "...DeliveryIdempotencyIndexTest"`)
- [ ] `./gradlew test` passes as a whole
- [ ] No production Java source file is added or modified by this task
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically **A10**: test (a) is the regression guard for the idempotency guard failing *open*, which is the exact failure ADR-003's own security table warns about

## Definition of Done

Tests written and passing locally against Testcontainers PostgreSQL. The completion note records the (c) seed size/status mix and any planner flakiness observed. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (dba) — current, written against the unpartitioned schema

Both test classes rewritten in place against V2/V3 as rewritten by TASK-002-03/04. Same two files, same package.

**(a)/(b) `DeliveryIdempotencyIndexTest`:** unchanged approach from the historical note (unique per-test `event_id`/`subscription_id` pairs via `UUID.randomUUID()`, plain `JdbcTemplate`, no Spring Data JDBC repository or domain type) but now asserts against the real mechanism: `idx_deliveries_live_pair`, a native partial unique index directly on `deliveries`, not `deliveries_live_index` + trigger. `secondLiveRowForSamePairIsRejected` is parameterized over all four live statuses (`PENDING`, `QUEUED`, `PROCESSING`, `RETRYING`); each asserts `DataIntegrityViolationException` with `getMostSpecificCause()` a `SQLException` whose `getSQLState()` is `23505`, and the message contains `idx_deliveries_live_pair` (possible now that there's one unambiguous index to check against, unlike the trigger-raised exception the old version dealt with). `secondRowAllowedOnceFirstIsTerminal` is parameterized over `DELIVERED`, `DEAD`, `FAILED`. `fullTransitionRejectsWhileLiveThenAcceptsAfterTerminal` walks reject-while-live -> move to `DEAD` -> accept-after-terminal in one sequential test. All 8 invocations pass.

**(c) `DeliveryDueQueryIndexTest`:** rewritten to drop everything partition-specific. No `pg_inherits` child-index-name resolution (the parent index name `idx_deliveries_due` now appears directly on the `Index Name` field of the plan node - there is exactly one index, not a family of per-partition children). No empty-`DEFAULT`-partition `Seq Scan` carve-out (there is no `DEFAULT` partition; `deliveries` is one table). Uses `EXPLAIN (FORMAT JSON)` **without** `ANALYZE` (no actual execution) since there is no longer a need to distinguish "empty partition, correctly seq-scanned" from "real regression" - with one plain table, any `Seq Scan` node on `deliveries` in the plan is unambiguously a failure, and not executing the query avoids running `FOR UPDATE SKIP LOCKED` outside a transaction. The plan JSON is parsed with `tools.jackson.databind` (Spring Boot 4.1.1 ships Jackson 3.x, package renamed from `com.fasterxml.jackson.databind` - discovered via a compile failure and corrected; flagging for other agents writing JSON-parsing test code against this codebase, since the package name is a real trap coming from Jackson 2.x muscle memory). `enable_seqscan` is never touched.

**Seed size and status mix (recorded per the task's requirement):** 5,000 `deliveries` rows across 25 subscriptions / 5 clients - 250 live (evenly split `PENDING`/`QUEUED`/`PROCESSING`/`RETRYING`, `next_attempt_at` 60s in the past so they are due) and 4,750 terminal (evenly split `DELIVERED`/`DEAD`/`FAILED`, `next_attempt_at` `NULL`). 5% live / 95% terminal, matching ADR-002 §2.1's own framing. `ANALYZE deliveries` and `ANALYZE subscriptions` run in `@BeforeAll` after seeding, before the single `@Test` runs `EXPLAIN`.

**Planner flakiness observed:** none. With no partitions, no empty-relation special case was needed; the plan reliably shows `idx_deliveries_due` used via `Index Scan` (or `Bitmap Heap Scan`/`Bitmap Index Scan`, whichever the planner chose against the seeded row/selectivity mix) and no `Seq Scan` node with `Relation Name: deliveries`.

**Files:** `src/test/java/com/cobre/challenge/schema/DeliveryIdempotencyIndexTest.java`, `src/test/java/com/cobre/challenge/schema/DeliveryDueQueryIndexTest.java`. `TestcontainersConfiguration` was already `public` (a prior task's fix) - not modified here.

**Verification:** `./gradlew test --tests "com.cobre.challenge.schema.DeliveryIdempotencyIndexTest"` and `--tests "com.cobre.challenge.schema.DeliveryDueQueryIndexTest"` each pass run independently. `./gradlew test` passes as a whole (all existing tests plus these two classes) against Testcontainers PostgreSQL, with V1-V3 applied by Flyway on context startup. No production Java source file (`src/main/**`) added or modified by this task. No `deliveries_live_index`, `trg_deliveries_live_index`, `pg_inherits` child-index resolution, or partition name appears anywhere in either test file.

No `git add`/`git commit` run.

## Completion note (dba) — historical, written against the partitioned schema

Retained as a record of the first implementation. The `TestcontainersConfiguration` visibility finding below still stands and does not need redoing; everything partition-specific (the `pg_inherits` child-index resolution, the empty-`DEFAULT`-partition `Seq Scan` carve-out, the note that the invariant runs through `deliveries_live_index` + triggers) is superseded by the unpartitioned schema.

**Files:**
- `src/test/java/com/cobre/challenge/schema/DeliveryIdempotencyIndexTest.java`
- `src/test/java/com/cobre/challenge/schema/DeliveryDueQueryIndexTest.java`

**Deviation flagged: `TestcontainersConfiguration` had to be widened from package-private to `public`.** The task requires these tests to live in `com.cobre.challenge.schema` and reuse `TestcontainersConfiguration` via `@Import(TestcontainersConfiguration.class)`. `TestcontainersConfiguration` (`src/test/java/com/cobre/challenge/TestcontainersConfiguration.java`) is declared `class TestcontainersConfiguration` (package-private) — a class from another package cannot reference it by name at all, so `@Import(TestcontainersConfiguration.class)` does not compile from `com.cobre.challenge.schema`, regardless of what the test does at runtime. Verified by hand: compiling from the subpackage against the unmodified file fails with `TestcontainersConfiguration is not public in com.cobre.challenge; cannot be accessed from outside package`. This is not something the task file could work around by writing different test code — the file path (`.../schema/...`) and the reuse instruction (`@Import(TestcontainersConfiguration.class)`, "do not modify it") are mutually incompatible as originally stated, given the class's existing visibility. Resolved with the minimal possible change: `class TestcontainersConfiguration` → `public class TestcontainersConfiguration` (one word, `src/test/java/com/cobre/challenge/TestcontainersConfiguration.java` line 11). No bean, no container image, no behavior changed — every existing test that uses it (`ChallengeApplicationTests`, `SqsQueueConfigurationTest` indirectly via its own container, etc.) is unaffected; confirmed by the full `./gradlew test` run below still passing. Flagging for architect awareness: any future test package placed outside `com.cobre.challenge` itself that needs this shared config will need the same visibility, so the widening is a one-time fix, not a per-task workaround.

### (a)/(b) `DeliveryIdempotencyIndexTest`

Uses `@Import(TestcontainersConfiguration.class) @SpringBootTest` + `JdbcTemplate`, no Spring Data JDBC repository, no domain/application type — plain SQL via `JdbcTemplate.update`. Each test method generates its own random `event_id`/`subscription_id`/`delivery_id`s (`UUID.randomUUID()`, `"EVT-" + UUID.randomUUID()`), so test methods share the same schema/container without needing `@Transactional` rollback or manual cleanup — chosen over rollback because a Postgres transaction that hits the expected `23505` unique violation is left in an aborted state (`current transaction is aborted, commands ignored until end of transaction block`) for any further statement in the *same* transaction, which would have complicated asserting-then-continuing inside one `@Transactional` test method. Unique per-test data sidesteps that entirely and keeps each test's SQL in its own implicit (autocommit) transaction per statement.

- `secondLiveRowForSamePairIsRejected` — `@ParameterizedTest @ValueSource({"PENDING","QUEUED","PROCESSING","RETRYING"})`. Inserts a live row, then a second live row for the same pair; asserts `DataIntegrityViolationException` is thrown (via `catchThrowableOfType`) and its `getMostSpecificCause()` is a `SQLException` with `getSQLState().equals("23505")`.
- `secondRowAllowedOnceFirstIsTerminal` — `@ParameterizedTest @ValueSource({"DELIVERED","DEAD","FAILED"})`. Inserts a live row, `UPDATE`s it to the terminal status, then inserts a second row for the same pair; asserts no exception.
- `fullTransitionRejectsWhileLiveThenAcceptsAfterTerminal` — single sequential test: live insert, second insert rejected, first row moved to `DEAD`, second insert then accepted.

All 8 test invocations (4 + 3 + 1) pass. Because the schema enforces the invariant via `deliveries_live_index` + triggers (TASK-002-03) rather than a partial unique index directly on `deliveries`, these assertions exercise the actual mechanism end to end without knowing its internals — exactly as the task instructs ("the assertion is on the invariant, not on the index's existence").

### (c) `DeliveryDueQueryIndexTest`

`@TestInstance(Lifecycle.PER_CLASS)` with an instance `@BeforeAll` that seeds once for the whole class (batch inserts via `JdbcTemplate.batchUpdate`), then `ANALYZE`s, then one `@Test` runs `EXPLAIN` on ADR-002 §2.1's exact query (join, full predicate, `ORDER BY next_attempt_at`, `LIMIT 500`, `FOR UPDATE SKIP LOCKED`, copied verbatim).

**Seed size and status mix (recorded per the task's requirement):** 5,000 `deliveries` rows across 25 subscriptions / 5 clients — 250 live (evenly split `PENDING`/`QUEUED`/`PROCESSING`/`RETRYING`, `next_attempt_at` set to 60s in the past so they are due) and 4,750 terminal (evenly split `DELIVERED`/`DEAD`/`FAILED`, `next_attempt_at` left `NULL`, matching the real terminal-transition writers). 5% live / 95% terminal, deliberately skewed toward terminal to reflect ADR-002 §2.1's own framing ("the table may hold tens of millions of rows while the index holds only what is outstanding") at a scale a unit test can seed in seconds rather than minutes.

**Planner-flakiness note (as the task asks me to record if hit):** the first run of this test failed — not because the due-query used the wrong index, but because the assertion was too strict. The seeded rows all land in the current month's partition (`deliveries_2026_09`), which the due-query correctly scans via `deliveries_2026_09_next_attempt_at_idx`. But the query also touches every other partition, including the always-empty `deliveries_default` (the `DEFAULT` partition created by V2, never populated by this test or by real traffic under normal operation). For that empty partition, Postgres correctly chooses a `Seq Scan` over an `Index Scan` — scanning zero rows costs nothing either way, and the planner is right to skip the index. My first assertion ("no `Seq Scan` node on any `deliveries*` relation") flagged that empty-partition `Seq Scan` as a failure, which would have been a false negative — exactly the kind of naive planner assertion the task warns against. Fixed by switching to `EXPLAIN (ANALYZE, FORMAT JSON)` (actual execution stats, not planner estimates) and narrowing the assertion to *substantive* sequential scans: a `Seq Scan` on a `deliveries*` relation is only a failure if `"Actual Rows" > 0`. This is a clearer assertion, not a weaker one — it still fails hard the moment a partition holding real rows gets sequentially scanned, which is the actual regression this test exists to catch; it just stops flagging a scan of a table Postgres correctly knows is empty. Did not force the planner (`enable_seqscan` untouched) and did not delete or skip the test, per the task's instruction.

Index-usage assertion resolves the *actual* per-partition index names via `pg_inherits` (parent `idx_deliveries_due` → its per-partition children, e.g. `deliveries_2026_09_next_attempt_at_idx`), because a partitioned index's own name never appears on an `Index Scan` node — only the auto-generated child index name does. Documented in the test's class Javadoc so this isn't mysterious to a future reader. Asserts at least one `Index Scan`/`Bitmap Index Scan` node uses a name from that family, and separately confirmed by inspection that `deliveries_2026_09` (the only partition holding rows in this seed) is scanned via `deliveries_2026_09_next_attempt_at_idx`, not sequentially.

### Verification

`./gradlew test --tests "com.cobre.challenge.schema.DeliveryIdempotencyIndexTest"` and `--tests "com.cobre.challenge.schema.DeliveryDueQueryIndexTest"` each pass run independently (confirming test-class independence), and `./gradlew test` passes as a whole (all existing tests plus these two new classes) against the real Testcontainers PostgreSQL instance, with V1-V3 applied by Flyway on context startup. No production Java source file (`src/main/**`) was added or modified by this task; the only non-test-class change is the one-line visibility fix to `TestcontainersConfiguration`, flagged above.

No `git add`/`git commit` run.
