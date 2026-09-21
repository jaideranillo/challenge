---
id: TASK-005-06
feature: FEAT-005
title: "NotificationEventJdbcRepository: ON CONFLICT (event_id) DO NOTHING, plus the authoritative read"
status: Ready for Review
agent: dba
depends_on: [TASK-005-03]
date: 2026-09-20
---

# TASK-005-06: `NotificationEventJdbcRepository`

## Feature

FEAT-005

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/NotificationEventJdbcRepository.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/mapper/NotificationEventRowMapper.java` (new)
- Concern: the `notification_events` adapter, implementing `NotificationEventRepositoryPort`.

`NamedParameterJdbcTemplate` (or `JdbcClient` over it), explicit SQL, bound parameters only — the convention TASK-004-05 and the four existing repositories already set. No `@Table` entity, no derived query.

`insertIfAbsent`:

```sql
INSERT INTO notification_events (event_id, client_id, event_type, content, created_at)
VALUES (:event_id, :client_id, :event_type, :content, :created_at)
ON CONFLICT (event_id) DO NOTHING
```

Return `affectedRows == 1`. Zero rows means the event was already stored — a normal outcome, returned as `false`, never thrown and never logged at error level.

`DO NOTHING`, not `DO UPDATE`. `notification_events` is append-only and immutable after insert (`V1`'s table comment: "Never updated. No updated_at by design"), and a second ingest carrying different `content` must not overwrite the first. The stored row wins; that is what makes it an audit record.

`created_at` is bound from the domain record's `createdAt` — **never** `now()` and never a column default. `V1`'s column comment is explicit that a `DEFAULT now()` "would silently mask a producer omission and corrupt the API date-range filter". Do not add one and do not emulate one.

`findById`: a plain `SELECT` on the primary key, returning `Optional.empty()` for no row (`EmptyResultDataAccessException` must not escape). The row mapper follows the existing `mapper/` package's conventions for `timestamptz` to `Instant`.

## Out of Scope

- Tests. TASK-005-07 owns them.
- Any `UPDATE` or `DELETE` against `notification_events`. Neither exists in this design.
- `deliveries` in any form — no join, no read, no write. TASK-005-08.
- `@Transactional` on the adapter. The use case owns the boundary.
- Any migration. `V1` is sufficient and is immutable.

## Acceptance Criteria

- [ ] Implements `NotificationEventRepositoryPort`; both methods, no extras.
- [ ] `insertIfAbsent` is one statement with `ON CONFLICT (event_id) DO NOTHING`, returning `affectedRows == 1`.
- [ ] No `DO UPDATE`, no `UPDATE`, no `DELETE` anywhere in the class.
- [ ] `created_at` is bound from the domain value; `now()` appears nowhere in the file.
- [ ] `findById` returns `Optional.empty()` rather than throwing on no row.
- [ ] Every value is a named bound parameter; no string concatenation into SQL.
- [ ] No log line, exception message or comment includes `content`.
- [ ] Method comments cite ADR-002 §1.1 step 3 and ADR-003 Amendment A5, and state that `false` is normal.
- [ ] Tests written and passing: owned by TASK-005-07, which must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A05:** bound parameters only. **A09:** `content` is the PII layer (ADR-002 §3.1) and never leaves this class except as a column value. **A10:** a duplicate is a `false`, not an exception.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
