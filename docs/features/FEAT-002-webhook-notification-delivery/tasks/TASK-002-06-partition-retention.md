---
id: TASK-002-06
feature: FEAT-002
title: Partition provisioning and retention-by-drop for deliveries and delivery_attempts
status: Deferred
agent: dba
depends_on: [TASK-002-04]
date: 2026-09-20
---

# TASK-002-06: Partition provisioning and retention-by-drop

> **DEFERRED — out of scope, no further code work expected.** Partitioning of `deliveries` and `delivery_attempts` is deferred entirely (ADR-003 §3, "Partitioning: deferred, not implemented" — a future performance/scalability improvement to revisit when data volume or retention pressure justifies it), so there is nothing to provision, nothing to drop, and no retention period to propose. This task's premise is gone with it. The file is kept, with its original scope and its completion note below, as the historical record of what was considered and why it was dropped; it is **not** to be picked up. Removal of the already-written `V4__partition_maintenance.sql` and `PartitionMaintenanceTest` is TASK-002-07. If partitioning is revisited, it returns through a superseding ADR and a fresh task, not by reopening this one.

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

TASK-002-03 and TASK-002-04 create each partitioned table with an initial, finite set of monthly partitions. That is enough to run and not enough to keep running: when the forward buffer is exhausted, every insert into `deliveries` fails (or lands in a `DEFAULT` partition that then blocks future `ATTACH`). This task closes both ends — creating the next partition ahead of time, and dropping old ones per ADR-003 §3's "archived/dropped by partition, not by row-level delete".

It is a separate task from 03 and 04 because it is a different concern (operational lifecycle, not schema shape) and because it is the one place in this feature where a **number the ADRs deliberately left open** has to be proposed.

- File(s) (target ~2, at most 3):
  - `src/main/resources/db/migration/V4__partition_maintenance.sql` (new) — the provisioning/drop routine
  - `src/test/java/com/cobre/challenge/schema/PartitionMaintenanceTest.java` (new) — Testcontainers test that the routine creates and drops the right partitions
- Concern: partition lifecycle. Nothing else.

### The retention period is yours to propose

ADR-003 §3 says explicitly: "Terminal rows (`DELIVERED`, `DEAD`) older than a retention window are archived/dropped by partition, not by row-level delete — **the DBA task owns the exact retention period**." No ADR states a number.

Propose one, and **label it a proposal rather than a derived value** — the same discipline ADR-002 §2.1 applies to its 60s staleness threshold and ADR-006 §1.2 applies to the breaker cooldown. State what it is a trade between: how far back a client complaint ("you never called me at 14:02") can be answered from `delivery_attempts`, versus table and index size on the write-hot table. If a legal or contractual retention requirement exists, it is not in any document in this repository — say so rather than assuming one.

### Provisioning

The routine must create the next month's partition **before** it is needed, with enough lead time that a failure has room to be noticed. Requirements:

- Idempotent: running it twice creates nothing extra and does not error.
- Covers **both** `deliveries` and `delivery_attempts`, which partition on different columns (`created_at` and `attempted_at`, see TASK-002-04) but on the same monthly boundaries.
- Creates the partition's indexes. On a partitioned parent, indexes created on the parent propagate to new partitions automatically — confirm this holds for **all** the indexes from TASK-002-03, including the partial ones and whatever mechanism carries the ADR-003 §2 idempotency invariant, and say so explicitly. If any index does not propagate, the routine must create it.

**What invokes the routine is a decision, not a given.** A Postgres-side routine (a function invoked by `pg_cron`, or by an external scheduler) and a Spring `@Scheduled` bean are both defensible. This task does **not** authorize writing a Spring `@Scheduled` bean: that is application code, it would be the first scheduled job in the codebase, and it belongs with the relay's scheduler (ADR-002 §2.1) rather than being introduced sideways by a DBA task. Deliver the SQL routine and **state the invocation options with a recommendation** in the completion note; if the recommendation is application-side scheduling, that becomes a follow-on task for another agent, flagged, not silently implemented here.

### Retention / drop

- Drop whole partitions, never `DELETE` rows (ADR-003 §3).
- **`deliveries` and `delivery_attempts` do not share a cut line.** TASK-002-04 flags this: a delivery created late in a month whose retries run into the next (ADR-004 §1's schedule reaches 6h intervals) has its `deliveries` row in one monthly partition and some `delivery_attempts` rows in the next. Dropping `deliveries` partition M while keeping `delivery_attempts` partition M+1 leaves orphaned attempt rows; dropping both at M loses attempt history for a still-retained delivery. Resolve this explicitly — the simplest correct rule is to drop `delivery_attempts` partitions on a lag behind `deliveries`, but choose, state, and justify whatever you pick.
- ADR-003 §3 scopes retention to **terminal** rows (`DELIVERED`, `DEAD`). A partition-level drop cannot discriminate by status: if a non-terminal row still sits in an old partition, dropping it destroys a delivery that is still live. That is a real conflict between "drop by partition" and "only terminal rows", and it must be handled, not assumed away. A pre-drop guard that refuses to drop a partition containing non-terminal rows — and raises rather than silently skipping — is the safe shape. A partition that keeps failing the guard is an operational signal (something is stuck far past its retry budget), which is worth surfacing rather than swallowing.
- Dropping must be **explicitly gated**, not automatic on first run in a fresh environment. A routine that drops data the first time it executes against a database someone just restored is a very expensive surprise.

### Test

One Testcontainers-PostgreSQL test (same environment rules as TASK-002-05: `@Import(TestcontainersConfiguration.class)`, real Postgres, no H2) asserting:

- Running the provisioning routine creates the expected next partition on both tables, and running it a second time is a no-op.
- A newly created partition carries every index the parent has.
- The drop path removes a partition older than the retention window and **refuses** to remove one containing a non-terminal row.

## Out of Scope

- Modifying `V1`, `V2` or `V3`. If the initial partition set from TASK-002-03/04 was wrong, that is a new migration, not an edit.
- Any Spring `@Scheduled` bean, any Java scheduling, any `application.yaml` scheduler config. Recommendation only — see above.
- Archival to cold storage / S3 / an export pipeline. ADR-003 §3 says "archived/dropped"; only the drop half is specified anywhere, and building an export pipeline off a one-word mention is a design decision that needs an ADR, not a DBA task.
- `pg_cron` as an infrastructure addition to `compose.yaml` or to any deployment. If the recommendation lands there, it is a devops-engineer follow-on task, flagged not implemented.
- Retention for `notification_events` and `subscriptions`. Neither is partitioned and no ADR asks for it.
- Any change to the four tables' columns or indexes.
- Any `INSERT` of business data. Test fixtures inside the test class are fine.

## Acceptance Criteria

- [ ] `V4__partition_maintenance.sql` provides an idempotent routine that creates the next monthly partition for both `deliveries` and `delivery_attempts`
- [ ] Newly created partitions carry every index the parent table has, verified in the test, including the partial ones and the ADR-003 §2 idempotency mechanism
- [ ] A retention period is proposed, stated as a proposal with its trade-off, and noted as not derived from any ADR or from any requirement present in this repository
- [ ] The drop path drops whole partitions and never issues a row-level `DELETE` against `deliveries` or `delivery_attempts`
- [ ] The drop path refuses (and reports) rather than proceeding when a candidate partition still holds a non-terminal row
- [ ] Dropping is explicitly gated and cannot run destructively by default in a fresh environment
- [ ] The `deliveries` / `delivery_attempts` cut-line mismatch is resolved with a stated rule, not left implicit
- [ ] `PartitionMaintenanceTest` passes against Testcontainers PostgreSQL and is independent of other tests
- [ ] Invocation mechanism is **recommended in the completion note**, not implemented as application code; any follow-on work is flagged for the appropriate agent
- [ ] `./gradlew test` passes
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically **A10**: an automatic, ungated drop is an error path that fails destructively; the guard and the gate are what keep it failing closed

## Definition of Done

Migration and test written, `./gradlew test` passing locally. The completion note records: the proposed retention period and its trade-off, the cut-line rule between the two tables, the index-propagation finding, and the recommended invocation mechanism with any follow-on task it implies. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Deferred <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) — Deferred: see the note at the top of this file; no further code work expected -->

## Completion note (dba) — historical, superseded by the deferral above

The work below was implemented and is being reverted by TASK-002-07. It is retained only as a record of what was built and what was learned.

**Files:**
- `src/main/resources/db/migration/V4__partition_maintenance.sql`
- `src/test/java/com/cobre/challenge/schema/PartitionMaintenanceTest.java`

### Retention period — proposal

**3 trailing complete calendar months for `deliveries`, 4 for `delivery_attempts`** (1 extra month of lag — see cut-line rule below). Stated as a proposal, not a derived value: no ADR and nothing else in this repository states a number or a legal/contractual retention requirement. Trade-off: how far back a client complaint ("you never called me at 14:02") can still be answered from `delivery_attempts` versus row/index size on the two write-hottest tables in the schema. Both numbers are function parameters (`retention_months`, `attempts_lag_months`), not hardcoded, so the proposal can be revised without a new migration if an actual requirement surfaces later.

### Provisioning

`provision_delivery_partitions(lead_months int DEFAULT 2)`: idempotent (checked by hand — running it twice back to back the second time returns zero rows and creates nothing; also asserted in `provisioningCreatesNextPartitionOnBothTablesAndIsIdempotent`), covers both tables against the same monthly boundary, and creates nothing beyond the two `CREATE TABLE ... PARTITION OF` statements — no explicit `CREATE INDEX` and no explicit trigger creation.

**Index/trigger propagation — confirmed, not assumed.** Verified twice: once by hand against a scratch container (a partition created via the function showed the same five named indexes plus PK, and the trigger, as an existing partition — `\d` output identical in shape), and again in `newlyCreatedPartitionCarriesEveryParentIndexAndTheIdempotencyTrigger`, which creates a partition via the function and asserts, via `pg_indexes` and `pg_trigger`, that it carries exactly the parent's index set (PK + 5, including `idx_deliveries_due`'s partial predicate, checked verbatim) and `trg_deliveries_live_index` — the ADR-003 §2 idempotency mechanism. All propagate automatically; the routine does not need to (and does not) create any of them itself.

### Retention / drop

Two functions, split deliberately so the destructive path cannot be reached by accident:
- `plan_delivery_partition_drops(retention_months, attempts_lag_months)` — read-only, issues no DDL, returns each past-retention partition's name and `action` (`eligible` or `blocked_non_terminal_rows`).
- `drop_delivery_partitions(retention_months, attempts_lag_months, confirm boolean DEFAULT false)` — with `confirm` left at its default, behaves identically to the planner (returns `eligible_not_confirmed` for anything it would otherwise drop) and drops nothing; only `confirm := true` executes `DROP TABLE`. Verified by hand and in `dropRefusesPartitionWithNonTerminalRowsAndOnlyDropsWhenConfirmed`: an unconfirmed call on an eligible partition leaves the table in place (checked via `pg_class`), and the same call with `confirm := true` drops it.

**Guard — verified against both a real blocked case and a real success case:** a partition containing a non-terminal `deliveries` row (or, for `delivery_attempts`, attempts belonging to a still-non-terminal delivery) is reported `blocked_non_terminal_rows` and a `RAISE WARNING` names it; it is never dropped regardless of `confirm`. Confirmed by hand (`RETRYING` row in an old partition blocked the drop, moving it to `DEAD` then made the same partition `eligible`) and in the test (`dropRefusesPartitionWithNonTerminalRowsAndOnlyDropsWhenConfirmed` walks the identical sequence: seed a `RETRYING` row in a manufactured 1999-01 partition, assert `blocked_non_terminal_rows`, transition to `DEAD`, assert `eligible` then `eligible_not_confirmed` then finally `dropped`).

Chosen to `RAISE WARNING` and continue (rather than `RAISE EXCEPTION` and abort the whole run) so one stuck partition doesn't block cleanup of unrelated older ones that are legitimately droppable — the loud-and-visible half of "raises rather than silently skipping" is the `WARNING` plus the returned `blocked_non_terminal_rows` row a caller can act on, not a hard stop.

**Cut-line rule (stated, not left implicit):** `delivery_attempts` partitions are retained `attempts_lag_months` (default 1) longer than the equivalent `deliveries` partitions. Rationale: a delivery created late in a month can have `delivery_attempts` rows land in the following month's partition (TASK-002-04, ADR-004 §1's 6h retry schedule), so dropping both tables at the identical cut line risks orphaning attempt rows for a still-retained delivery. Retaining `delivery_attempts` one extra month keeps it behind that possibility with margin.

**Drop mechanism:** `EXECUTE format('DROP TABLE %I', ...)` — whole-partition drop, never a row-level `DELETE`. Confirmed both by reading the function body (there is no `DELETE FROM` anywhere in it) and by the test asserting the dropped partition is gone from `pg_class` entirely, not merely emptied.

### Invocation mechanism — recommended, not implemented

**Recommendation: `pg_cron`**, scheduling `SELECT provision_delivery_partitions();` monthly and `SELECT drop_delivery_partitions(confirm := true);` monthly (after an operator or a separate alerting step has reviewed `plan_delivery_partition_drops()`'s output, since `confirm := true` is itself the point where a human or an automated policy takes responsibility for the drop). Reasons over a Spring `@Scheduled` bean, per the task's explicit prohibition on writing one here: this is the first scheduled job of any kind in the codebase, `pg_cron` keeps partition lifecycle colocated with the database it's maintaining rather than coupling it to the application's deploy/restart lifecycle (a partition still needs to roll over even if the app is down), and it doesn't compete with the relay's own 5s scheduled job (ADR-002 §2.1) for whatever scheduling infrastructure the application eventually adopts.

**Follow-on work flagged, not implemented here:**
- Adding `pg_cron` to `compose.yaml` / any deployment is a `devops-engineer` task (explicitly out of scope for this migration, per the task file).
- Wiring the actual cron schedule (`SELECT cron.schedule(...)`) is either a further DBA migration once `pg_cron` is confirmed available, or application-side scheduling if the architect prefers colocating it with the relay's own scheduled job — an open choice for the architect, not decided here.
- Whether `drop_delivery_partitions(confirm := true)` runs unattended on a schedule, or only after a human reviews `plan_delivery_partition_drops()`'s output, is an operational policy decision beyond this task's scope; the gate makes either choice possible without a code change.

### Verification

`./gradlew test --tests "com.cobre.challenge.schema.PartitionMaintenanceTest"` passes standalone (test independence). `./gradlew test` passes as a whole, Flyway applying V1-V4 against the real Testcontainers PostgreSQL instance. All hand-verification above was run against a disposable scratch `postgres:18` container in addition to the automated test, exercising: idempotent provisioning (0-row second run), index/trigger propagation onto a function-created partition, the non-terminal-row guard (both the blocked and the subsequently-eligible case), the `confirm` gate (both `false` and `true`), and an actual `DROP TABLE`.

No `git add`/`git commit` run.
