---
id: TASK-004-05
feature: FEAT-004
title: "Row mappers and Postgres type-mapping conventions for the three aggregates"
status: Ready for Review
agent: dba
depends_on: [TASK-004-01, TASK-004-02]
date: 2026-09-20
---

# TASK-004-05: row mappers

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The `ResultSet` to domain mapping every later adapter task reuses. Doing it once here is what keeps each adapter task down to its own SQL.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/mapper/DeliveryRowMapper.java`
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/mapper/DeliveryAttemptRowMapper.java`
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/mapper/SubscriptionRowMapper.java`
- Concern: `ResultSet` to domain type, and nothing else.

Implement `org.springframework.jdbc.core.RowMapper<T>`. Spring types are fine here: this is the adapter layer.

### Type-mapping rules

| Column shape | Rule |
|---|---|
| `timestamptz` | `rs.getObject(col, OffsetDateTime.class).toInstant()`, never `getTimestamp` (which applies the JVM default zone) |
| nullable `timestamptz` | the above, `Optional.ofNullable`-wrapped; a null read as a primitive is a silent zero |
| native enum (`delivery_status`, `circuit_state`, `verification_state`, `delivery_origin`) | `rs.getString(col)` then `Enum.valueOf`; the reverse direction needs an explicit cast and belongs to the writing task |
| `text[]` (`event_types`) | `(String[]) rs.getArray(col).getArray()` then `Set.copyOf`/`List.copyOf` |
| `interval` (`circuit_backoff`) | read as `org.postgresql.util.PGInterval` or as `String`, convert to `java.time.Duration`; document which and why in the class javadoc |
| `uuid` | `rs.getObject(col, UUID.class)` |

### `DeliveryRowMapper` specifics

Maps every component of the amended `Delivery` (TASK-004-02), including the two new ones:

- `event_created_at` -> `eventCreatedAt`, a required `Instant`. If the column reads null, that is a bug in the insert, not a case to tolerate; let it fail loudly rather than substituting `created_at`, which would reintroduce exactly the replay-timestamp bug ADR-003 A4 exists to prevent.
- `trace_context` -> `Optional<String> traceContext`, `Optional.ofNullable`.
- `created_at` and `updated_at` are **not** mapped. They are read by no use case and are not components of `Delivery` (ADR-003 A3).

Every mapper is stateless and holds no `Clock` and no `ThreadLocal`. Declare them as Spring beans or instantiate them as constants in the consuming adapter; either is fine, but no mutable state, since these are shared across virtual threads.

## Out of Scope

- No SQL. Not one statement; mappers read a `ResultSet` they are handed.
- No adapter class and no repository interface implementation.
- No `Delivery` construction path other than the canonical constructor. No builder.
- No `created_at`/`updated_at` mapping.
- No public `delivery_status` translation. The mapper reads the internal enum; the public mapping is ADR-003 §1's and belongs to the web adapter.
- No `NotificationEvent` mapper. Nothing in this feature reads that table into a domain object.

## Acceptance Criteria

- [ ] Three `RowMapper` implementations exist, stateless, no mutable fields.
- [ ] Every `timestamptz` goes through `getObject(..., OffsetDateTime.class).toInstant()`; no `getTimestamp` anywhere.
- [ ] Nullable timestamps are `Optional`-wrapped; no primitive default can mask a null.
- [ ] `DeliveryRowMapper` maps `event_created_at` as a required `Instant` and `trace_context` as `Optional<String>`, and maps neither `created_at` nor `updated_at`.
- [ ] `event_types` maps through `getArray`, not string splitting.
- [ ] `circuit_backoff` maps to `java.time.Duration` and the javadoc states the conversion used.
- [ ] No `synchronized` block and no `ThreadLocal` in any mapper.
- [ ] Tests written and passing: one Testcontainers test per mapper that inserts a row with plain SQL (every nullable column both null and populated), selects it, and asserts round-trip equality of every component. For `Delivery`, assert specifically that `eventCreatedAt` is the event's timestamp and **not** the delivery row's `created_at`, using a fixture where the two deliberately differ. Real Postgres via `TestcontainersConfiguration`; no H2, no mocked `ResultSet`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. SRP: mapping only, no querying.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A09:** no mapper logs a mapped value; `content` and `response_excerpt` are the PII layer (ADR-002 §3.1) and must never reach a log or an exception message from here.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
