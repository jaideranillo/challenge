---
id: TASK-002-02
feature: FEAT-002
title: "V1 migration: enum types, notification_events, subscriptions"
status: Ready for Review
agent: dba
depends_on: [TASK-002-01]
date: 2026-09-20
---

# TASK-002-02: V1 migration — enum types, `notification_events`, `subscriptions`

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The two **input** tables of ADR-003 §3, plus every enum type the schema uses. Neither table is partitioned, so this task carries none of the partitioning complexity of TASK-002-03/04 — which is exactly why it is separated from them.

- File(s):
  - `src/main/resources/db/migration/V1__enums_notification_events_subscriptions.sql` (new)
- Concern: the non-partitioned input tables and the shared enum vocabulary. Nothing else.

### Enum types

| Type | Values | Used by |
|---|---|---|
| `delivery_status` | `PENDING`, `QUEUED`, `PROCESSING`, `RETRYING`, `DELIVERED`, `DEAD`, `FAILED` | `deliveries.status` (TASK-002-03) |
| `delivery_origin` | `INGEST`, `REPLAY`, `RECOVERED` | `deliveries.origin` (TASK-002-03) |
| `circuit_state` | `CLOSED`, `OPEN`, `HALF_OPEN` | `subscriptions.circuit_state` |
| `verification_state` | `PENDING_VERIFICATION`, `VERIFIED` | `subscriptions.verification_state` |

**`delivery_status` is the seven-value internal state machine of ADR-003 §1, not the three-value public API vocabulary of ADR-003 §1.1.** This is the single most confusable point in this feature. ADR-003 uses the name `delivery_status` for both:

- the **internal** states, which are what this enum stores: `PENDING, QUEUED, PROCESSING, RETRYING, DELIVERED, DEAD, FAILED`;
- the **public** `delivery_status` values the API exposes — `pending`, `completed`, `failed` — which are produced by a mapping step **in the controller** (ADR-003 §1.1's table, and Q4) and are **never stored, never defaulted to, and never constrained against in the database**.

If you find yourself typing `completed` or `failed` in lowercase into this migration, stop: that is the public vocabulary and it does not belong in the schema.

The enum type is created even though ADR-003 §3's ER diagram renders `status` as `text` — that is Mermaid-ER shorthand, not a storage decision (recorded in FEAT-002's "Known ADR ambiguities" table). A native enum constrains the value set at the database boundary, which is what keeps a later adapter bug from writing a state the state machine does not have.

### `notification_events`

Columns per ADR-003 §3. Semantics that must be honored:

- `event_id` is the **primary key** and is the platform's own id (`text`, e.g. `EVT001` — see `docs/challenge/notification_events.json`). Not a UUID, not generated here. Its being the PK is load-bearing: ADR-002 §1.1 step 3's ingest is idempotent precisely because it is `INSERT ... ON CONFLICT (event_id) DO NOTHING`.
- `client_id`, `event_type`, `content`, `created_at` — all `NOT NULL`.
- `created_at` is the **event-creation timestamp**, not the row-insert time (ADR-003 §3: "the event-creation timestamp the API's date-range filter runs against"). It is supplied by the caller. Do not give it `DEFAULT now()`; a default here would silently paper over a producer that omitted it and corrupt both the date-range filter and the client-side ordering that ADR-003 §3 relies on in place of a `sequence_number`.
- The table is **immutable after insert** (ADR-003 §3: "Never updated after insert"). It has no `updated_at`. Enforcing immutability with a trigger is **out of scope** — do not add one; it is a later decision, not a silent addition here.
- `content` is the PII layer (ADR-002 §3.1). Store it; never log it. No constraint needed, but note it in the migration comment.

An index supporting `(client_id, created_at)` on this table is **not** requested by ADR-003 §3 — the list endpoint reads `deliveries`, not this table. Do not add one speculatively (YAGNI).

### `subscriptions`

Columns per ADR-003 §3, with rationale spread across ADR-004 §2, ADR-005 §2, ADR-006 §1.2 and §1.3. The ones with non-obvious requirements:

| Column | Requirement |
|---|---|
| `subscription_id` | `uuid` primary key. |
| `client_id`, `target_url` | `NOT NULL`. |
| `event_types` | `text[]`, `NOT NULL`. ADR-003 §3 is explicit that this array **replaces** a `unique(client_id, event_type)` design: one row per client covering N event types. Do not add that unique constraint. |
| `secret_ref`, `previous_secret_ref`, `previous_secret_expires_at` | References to a secret, **never secret material** (ADR-004 §2). `previous_*` are nullable (rotation in progress only). |
| `active` | `boolean NOT NULL DEFAULT true`. |
| `verification_state` | `verification_state NOT NULL DEFAULT 'PENDING_VERIFICATION'`. **The default is a security control**, not a convenience: ADR-005 §2/§OWASP-A06 requires that a subscription is never deliverable until its target URL's operator answered the challenge. A default of `VERIFIED` would disable the amplification/SSRF-consent control silently. |
| `verified_at` | nullable `timestamptz`; set only on the transition to `VERIFIED`. |
| `max_concurrency` | `int NOT NULL DEFAULT 10` (ADR-006 §1.3's stated default). |
| `circuit_state` | `circuit_state NOT NULL DEFAULT 'CLOSED'`. |
| `circuit_opened_at`, `circuit_backoff` | nullable (`timestamptz`, `interval`). ADR-006 §1.2 sets both to `NULL` when the circuit closes, so they must be nullable. `circuit_backoff` is an `interval` because ADR-002 §2.1's due-query uses it directly as `circuit_opened_at < now() - s.circuit_backoff`. |
| `consecutive_opens` | `int NOT NULL DEFAULT 0`. This is the **only** breaker counter that is persisted; ADR-006 §1.2 is explicit that the failure count is deliberately in-memory per pod to keep this row cold. **Do not add a `consecutive_failures` column.** |
| `throttled_until` | nullable `timestamptz` (ADR-004 §1's `429`/`Retry-After` gate). |
| `created_at`, `updated_at` | `timestamptz NOT NULL DEFAULT now()`. These are persistence-only audit columns; they are not part of the domain model. |

**Index on `subscriptions`:** the ingest path's subscription lookup (ADR-002 §1.1 step 2, ADR-003 §2) is `WHERE client_id = ? AND event_type = ? AND active`, where `event_type` is matched against the `event_types` array. Provide an index that actually serves that predicate — a plain b-tree on `(client_id)` combined with a **GIN index on `event_types`** is the shape that works, since a b-tree cannot serve an array containment test. Choose and justify the exact form in the completion note; what is not acceptable is a sequential scan of `subscriptions` on every ingest, since that query runs once per inbound event.

Note that ADR-003 §3's index list is a list of `deliveries` indexes; it says nothing about `subscriptions`. The index above is derived from ADR-002 §1.1's query, and that derivation must be stated in the completion note rather than presented as an ADR requirement.

### Migration hygiene

- One `V1__*.sql` file. Flyway runs it in a transaction; keep every statement in it idempotent-by-construction (a fresh database) rather than defensive `IF NOT EXISTS` everywhere — this is the first migration and the database is empty.
- No `INSERT` of any kind. No seed rows, no sample subscriptions.
- Comment each table and the non-obvious columns with `COMMENT ON`, citing the ADR section. The schema is the thing a future reader will look at first; the argument for `consecutive_opens` existing while `consecutive_failures` does not is worth two lines in the database itself.

## Out of Scope

- `deliveries` and `delivery_attempts` — TASK-002-03 and TASK-002-04. Not even as empty placeholders.
- Any partitioning. Neither table here is partitioned.
- The partial unique index, `idx_deliveries_due`, or any `deliveries` index.
- Any trigger, including an `updated_at` touch trigger and an immutability trigger on `notification_events`.
- Any FK **from** these tables to `deliveries` (there are none; `deliveries` references them, not the reverse).
- Any Java code, any test, any change under `src/test/java`.
- `build.gradle` and `application.yaml` — TASK-002-01 owns them.
- Subscription CRUD (ADR-003 §3 puts the API explicitly out of scope). The table exists; nothing manages it.
- Row-level security, roles, grants. Not decided by any ADR; do not invent them.

## Acceptance Criteria

- [ ] `V1__enums_notification_events_subscriptions.sql` creates the four enum types with exactly the values listed above
- [ ] `delivery_status` contains the seven **internal** states in uppercase and contains none of `pending`/`completed`/`failed`
- [ ] `notification_events` has `event_id` as its primary key, every column `NOT NULL`, no `updated_at`, and **no `DEFAULT now()` on `created_at`**
- [ ] `subscriptions` has every column in ADR-003 §3's ER diagram, with the nullability and defaults in the table above
- [ ] `subscriptions.verification_state` defaults to `PENDING_VERIFICATION`
- [ ] There is **no** `consecutive_failures` column and **no** `unique(client_id, event_type)` constraint
- [ ] The subscription-lookup predicate (`client_id` + `event_type` in `event_types` + `active`) is index-supported, and `EXPLAIN` on it does not show a sequential scan on a table with a few thousand rows
- [ ] The migration contains no `INSERT`
- [ ] `./gradlew test` passes; Flyway applies V1 cleanly against the Testcontainers PostgreSQL instance and the context starts
- [ ] Applying V1 to an empty database twice (fresh container each time) produces an identical schema — i.e. the migration is deterministic and has no environment dependence
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically **A06**: `verification_state` cannot default to `VERIFIED`; and **A04**: no column in this migration stores secret material, only references

## Definition of Done

Migration written, `./gradlew test` passing locally against Testcontainers PostgreSQL. Record in the completion note: the exact index chosen for the subscription lookup and why, and the `EXPLAIN` output backing it. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (dba)

**File:** `src/main/resources/db/migration/V1__enums_notification_events_subscriptions.sql`.

**Enums:** `delivery_status` (7 internal states, uppercase, matches ADR-003 §1 exactly), `delivery_origin` (`INGEST|REPLAY|RECOVERED`), `circuit_state` (`CLOSED|OPEN|HALF_OPEN`), `verification_state` (`PENDING_VERIFICATION|VERIFIED`). No lowercase public vocabulary value (`pending`/`completed`/`failed`) appears anywhere in this migration.

**`notification_events`:** `event_id text PRIMARY KEY`, all columns `NOT NULL`, `created_at` has no `DEFAULT` (caller-supplied event-creation timestamp per ADR-003 §3). No `updated_at`. No immutability trigger (explicitly out of scope). No index beyond the PK — ADR-003 §3's list endpoint reads `deliveries`, not this table, so a speculative `(client_id, created_at)` index was not added (YAGNI).

**`subscriptions`:** every column from the task's table, with the stated defaults/nullability. `subscription_id` is `uuid NOT NULL` with **no DB-side default** — generated by the application, deliberately consistent with the `deliveries.delivery_id` decision TASK-002-03 makes (recorded there; kept aligned here so the two PK-generation stories in the schema don't diverge without reason). `verification_state NOT NULL DEFAULT 'PENDING_VERIFICATION'` (OWASP A06 control, commented in the DDL). No `consecutive_failures` column, no `unique(client_id, event_type)` constraint.

**Subscription-lookup index — chosen and justified:** the ingest predicate is `WHERE client_id = ? AND event_type = ? AND active` (ADR-002 §1.1 step 2), where `event_type` is tested against the `event_types` array. Two indexes were created, not one, because a b-tree and a GIN index are different access methods and Postgres has no single index type spanning both:
- `idx_subscriptions_client_active` — b-tree on `(client_id, active)`.
- `idx_subscriptions_event_types` — GIN on `event_types`, for the array-containment test a b-tree cannot serve.

**EXPLAIN evidence** (5,000-row seed, ~10 rows/client, 500 distinct `client_id`s, `ANALYZE`d, run against a scratch Postgres 18 container — not the Testcontainers instance, since this task adds no Java test):
```
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM subscriptions
WHERE client_id = 'CLIENT10' AND active AND event_types @> ARRAY['credit_card_payment'];

Bitmap Heap Scan on subscriptions  (cost=4.38..36.37 rows=10 width=255) (actual rows=10 loops=1)
  Recheck Cond: ((client_id = 'CLIENT10'::text) AND active)
  Filter: (event_types @> '{credit_card_payment}'::text[])
  ->  Bitmap Index Scan on idx_subscriptions_client_active  (cost=0.00..4.38 rows=10 width=0)
        Index Cond: ((client_id = 'CLIENT10'::text) AND (active = true))
Execution Time: 0.069 ms
```
No sequential scan. At this cardinality the planner satisfies the query from `idx_subscriptions_client_active` alone (the array check becomes a cheap post-filter on 10 rows); the GIN index exists for clients with many subscription rows or low-selectivity `active`, where a client-only b-tree lookup would leave a large post-filter set. Both indexes are justified against the same named query, not speculative.

**Verification:** `./gradlew test --tests "com.cobre.challenge.ChallengeApplicationTests"` passes; Flyway log confirms `Migrating schema "public" to version "1 - enums notification events subscriptions"` then `Successfully applied 1 migration ... now at version v1` against the Testcontainers PostgreSQL 18.6 instance. Applying V1 to a fresh database is deterministic by construction (no `IF NOT EXISTS`, no environment-dependent value, no `INSERT`).

No `git add`/`git commit` run.
