---
id: TASK-002-04
feature: FEAT-002
title: "V3 migration: delivery_attempts table"
status: Ready for Review
agent: dba
depends_on: [TASK-002-03]
date: 2026-09-20
---

# TASK-002-04: V3 migration — `delivery_attempts`

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Supersedes its own earlier scope — read this first

This file previously specified a **range-partitioned** `delivery_attempts` table (monthly on `attempted_at`) with a composite `PRIMARY KEY (id, attempted_at)` and **no foreign key** to `deliveries` — the FK was dropped because `deliveries`' own composite PK left `delivery_id` without a unique constraint to reference.

Partitioning is now deferred entirely (ADR-003 §3, rewritten, at the user's direction). TASK-002-03 has been rewritten to give `deliveries` a plain single-column `PRIMARY KEY (delivery_id)`, so both of those consequences go away. Rewrite `V3__delivery_attempts.sql` in place — it is working-tree-only and uncommitted, so replace its contents rather than stacking a corrective migration.

## Scope

The append-only attempt history of ADR-003 §3.

- File(s):
  - `src/main/resources/db/migration/V3__delivery_attempts.sql` (rewritten)
- Concern: the `delivery_attempts` table and its indexes. Nothing else.

### Columns (ADR-003 §3)

| Column | Notes |
|---|---|
| `id` | `bigint` identity, **`PRIMARY KEY` on its own**. Not a public identifier — nothing in ADR-003 or ADR-005 exposes it. |
| `delivery_id` | `uuid NOT NULL`, **FK to `deliveries(delivery_id)`** — see below. |
| `attempt_number` | `int NOT NULL`. Mirrors `deliveries.attempt_count` at the time of the attempt and is carried in the signed outbound body (ADR-004 §1.1). |
| `http_status` | nullable `int`. Null when the attempt failed before an HTTP response existed (connect timeout, DNS failure, URL revalidation failure — ADR-003 §3 names that case explicitly). |
| `response_time_ms` | nullable `int`. |
| `response_excerpt` | nullable, **length-bounded** `varchar(n)`. See the A09 note below. |
| `error` | nullable `text`. The error class or message for a failure with no HTTP response. |
| `attempted_at` | `timestamptz NOT NULL DEFAULT now()`. An ordinary column — **not** a partition key and not part of any key. |

There is deliberately **no `outcome` enum column**: ADR-003 §3 states success/retryable/non-retryable is derived from `http_status`/`error` using ADR-004 §1's classification (including its 3xx / 408 / 429 nuances), not stored redundantly. Do not add one. Do not add a JSONB catch-all either — ADR-003 §3 explains why the table is columnar rather than a JSONB blob on `deliveries` (the monitoring queries: p95 latency per client, failure rate over a window).

Do **not** add a `created_at` column, and do **not** add a `delivery_created_at` column — the latter existed in the old scope only as a way to make a composite FK into a partitioned parent possible, and that problem no longer exists.

### No partitioning

`delivery_attempts` is a **plain, unpartitioned table**. No `PARTITION BY`, no child partitions, no `DEFAULT` partition, and therefore no partition-key reading to record and no cross-month cut-line problem to flag onward (the old scope's note to TASK-002-06 on that subject is moot — TASK-002-06 is closed).

### Foreign key to `deliveries` — required, plain, single-column

`FOREIGN KEY (delivery_id) REFERENCES deliveries(delivery_id)`. TASK-002-03 restores a single-column PK on `deliveries.delivery_id`, so this is creatable with no composite key, no redundant carried column, and no application-level substitute. **Dropping it is not an acceptable outcome this time** — if it turns out not to be creatable, stop and report rather than omitting it.

State the `ON DELETE` behavior you choose and why. Nothing in the ADRs deletes a `deliveries` row, so the default (`NO ACTION`) is defensible; `ON DELETE CASCADE` is also defensible if a future retention story deletes parents. Pick one and justify it in one line — do not leave it unstated.

### Indexes

ADR-003 §3 lists no indexes for this table. Add only what a named query needs, and name the query:

- `(delivery_id, attempt_number)` — "full history behind a single complaint" (ADR-003 §3) and the DLQ observer's correlation by `delivery_id`, plus an ordered walk through one delivery's attempts with no extra sort. Serves plain `delivery_id` lookups through its leading column. This one is clearly needed.
- Anything else — the monitoring aggregates (p95 endpoint latency per client, failure rate over a window, ADR-003 §3) — is a **later, measured** decision. Do not speculatively index `attempted_at` or `http_status` now. Note that with partitioning deferred there is no longer partition pruning covering the time dimension; that is a known, accepted consequence of the deferral, not a licence to add a speculative index here. If a real time-range query appears later, it gets its own index then, against a named query.

### A09 note on `response_excerpt`

ADR-003 §3 requires it be truncated and never contain signature headers or secrets; ADR-002 §3.1 adds that it is PII and must never reach a log, a span attribute, or MDC. The schema's contribution is a **hard length bound at the column level**, so that a truncation bug in the later adapter cannot store an unbounded client response body. Pick a bound (a few hundred to a couple of thousand characters is the range an "excerpt" implies) and state the number and the reasoning in the completion note — it is a proposal, not a derived value. The "never logged" half is a constraint on the later adapter; restate it in a `COMMENT ON COLUMN` so it is visible where the column is.

## Out of Scope

- `deliveries` — TASK-002-03. Do not modify `V2__deliveries.sql`.
- Any partitioning, partition maintenance, or retention mechanism. Deferred by ADR-003 §3.
- Any test — TASK-002-05.
- Any Java code, any `src/test/java` change, `build.gradle`, `application.yaml`.
- Any `INSERT` or seed data.
- An `outcome` column, a JSONB column, or any denormalization of attempt classification.
- Any trigger or function. This migration contains zero `CREATE TRIGGER` and zero `CREATE FUNCTION`.
- The DLQ-observer component that ADR-003 §3 mentions as a possible consumer of this table. Not built, not in scope.

## Acceptance Criteria

- [ ] `V3__delivery_attempts.sql` creates `delivery_attempts` with exactly the columns above, no `outcome` column, no JSONB catch-all, no `created_at`, no `delivery_created_at`
- [ ] The table is **not** partitioned: no `PARTITION BY`, no partition children, no `DEFAULT` partition
- [ ] `PRIMARY KEY (id)`, single column
- [ ] `FOREIGN KEY (delivery_id) REFERENCES deliveries(delivery_id)` exists, single-column, with a stated `ON DELETE` choice
- [ ] `response_excerpt` has an explicit length bound, and the bound is stated and justified in the completion note
- [ ] `http_status`, `response_time_ms`, `error` and `response_excerpt` are all nullable, so the attempted-but-no-HTTP-response case (ADR-003 §3) is representable without placeholder values
- [ ] Exactly one index beyond the PK — the `(delivery_id, attempt_number)` lookup — unless an additional one is justified against a named query in the completion note
- [ ] `./gradlew test` passes; Flyway applies V1, V2, V3 cleanly against Testcontainers PostgreSQL
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically **A09**: `response_excerpt` is length-bounded at the schema level and documented as never-loggable

## Definition of Done

Migration rewritten, `./gradlew test` passing locally. The completion note must record: the single-column PK, the restored FK and its `ON DELETE` choice, the `response_excerpt` bound and its reasoning, and confirmation that no partitioning artefact remains. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (dba)

**File:** `src/main/resources/db/migration/V3__delivery_attempts.sql` (rewritten in place, not stacked).

**Single-column PK:** `PRIMARY KEY (id)`, `id bigint GENERATED ALWAYS AS IDENTITY`. No composite key, no `attempted_at` in the key.

**Restored FK and `ON DELETE` choice:** `fk_delivery_attempts_delivery`: `FOREIGN KEY (delivery_id) REFERENCES deliveries (delivery_id)`, single-column, plain (no composite, no carried `delivery_created_at`). Left at the default `NO ACTION` (not written explicitly) - nothing in the ADRs deletes a `deliveries` row today, so there is no cascade scenario to design for; if a future retention story deletes parents, `ON DELETE CASCADE` would be the candidate then, revisited against that story rather than pre-empted here.

**`response_excerpt` bound and reasoning:** `varchar(1000)`. Chosen as comfortably within the "a few hundred to a couple thousand" range the task names - enough to capture a diagnosable slice of a client error body while still bounding worst-case row size. This is a hard schema-level backstop, not a substitute for the adapter-layer truncation; the "never logged, never on a span, never in MDC" requirement (A09, ADR-002 §3.1) is restated via `COMMENT ON COLUMN delivery_attempts.response_excerpt`.

**Confirmation no partitioning artefact remains:** no `PARTITION BY`, no child partitions, no `DEFAULT` partition, no `delivery_created_at` column, no `outcome` column, no JSONB catch-all, no `created_at` column. Verified by reading the file back after writing it.

**Nullability:** `http_status`, `response_time_ms`, `error`, `response_excerpt` are all nullable, so the attempted-but-no-HTTP-response case (URL revalidation failure, connect timeout, DNS failure) is representable without placeholder values.

**Index:** exactly one beyond the PK - `idx_delivery_attempts_delivery_attempt` on `(delivery_id, attempt_number)`, serving "full history behind a single complaint" and DLQ-observer correlation by `delivery_id`. No speculative `attempted_at`/`http_status` index added.

**Verification:** `./gradlew test` passes; Flyway applies V1 -> V2 -> V3 cleanly against Testcontainers PostgreSQL, with the FK to `deliveries(delivery_id)` creating successfully (proves TASK-002-03's single-column PK claim in practice, not just in theory).

No `git add`/`git commit` run.
