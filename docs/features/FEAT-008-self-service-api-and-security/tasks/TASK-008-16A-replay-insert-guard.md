---
id: TASK-008-16A
feature: FEAT-008
title: insertReplayIfAbsent — the DELIVERED half of ADR-005 §1's 409, enforced inside the INSERT
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-21
---

# TASK-008-16A: `insertReplayIfAbsent`

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Why this task exists (Tech Lead review of 2026-09-21)

ADR-005 §1 states two 409 conditions for replay: a **non-terminal** row already exists for the
`(event_id, subscription_id)` pair, **or** an already-`DELIVERED` row does.

`idx_deliveries_live_pair` (V2) covers only the first: its predicate is
`status NOT IN ('DELIVERED', 'DEAD', 'FAILED')`, so a `DELIVERED` row is deliberately outside it —
which is correct for the index's own job (ADR-003 §2's idempotency invariant is about *live* rows).
The consequence is that **the `DELIVERED` half of ADR-005 §1's 409 is enforced nowhere today.**

The Tech Lead raised it as a two-transaction race (the pair goes `DELIVERED` between the replay
use case's read and its insert). The gap is in fact wider than the race: TASK-008-19's flow reads
the *target row by id* and checks that it is `DEAD`; it never asks whether some *other* row for the
same pair is `DELIVERED`. There is no check to race against. Both the missing check and the race
close the same way, and only one way closes both: **evaluate the condition inside the INSERT
statement**, where PostgreSQL evaluates it against the committed state at insert time.

This is not scope creep and needs no ADR amendment: ADR-005 §1 already requires the behavior,
ADR-005 §1 explicitly leaves port shapes "to be finalized in the feature breakdown", and ADR-007
§5.2's own port table already names an `insertReplay` method on the delivery port.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryPipelineRepositoryPort.java`
    (modified — **one new method**, nothing else touched)
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java`
    (modified — the new method's SQL)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryIdempotentInsertTest.java`
    (modified — **deferred this phase**; new cases specified below, not written now)
- Concern: one guarded insert statement.

## The new port method

```
Optional<Delivery> insertReplayIfAbsent(Delivery delivery);
```

Same shape and same contract as `insertIfAbsent` — `Optional.empty()` means "a row already exists
that forbids this replay", a present value is the inserted row — with one additional condition.

**`insertIfAbsent` is NOT modified.** Its current semantics ("a `DELIVERED` row frees the pair")
are the ingest path's semantics, they are merged, and they are asserted by
`DeliveryIdempotentInsertTest.insertIfAbsent_afterDelivered_freesThePair_insertsNewRow`. Adding the
`DELIVERED` condition to the shared method would change ingest behavior and break that test.
Replay and ingest genuinely want different rules here, so they get different methods — ISP, and the
difference becomes visible in the type system instead of living in a comment.

## The statement

Keep the existing `INSERT ... ON CONFLICT (event_id, subscription_id) WHERE <LIVE_STATUS_PREDICATE>
DO NOTHING RETURNING ...` exactly as `insertIfAbsent` has it — `LIVE_STATUS_PREDICATE` must stay the
single shared constant, because it has to match `idx_deliveries_live_pair` verbatim for the
`ON CONFLICT` inference to resolve at all.

Add the `DELIVERED` condition as a `WHERE NOT EXISTS` on the INSERT's source, so it is evaluated by
the same statement:

```
INSERT INTO deliveries (<cols>)
SELECT <values>
WHERE NOT EXISTS (
  SELECT 1 FROM deliveries
   WHERE event_id = :event_id
     AND subscription_id = :subscription_id
     AND status = 'DELIVERED'::delivery_status)
ON CONFLICT (event_id, subscription_id) WHERE <LIVE_STATUS_PREDICATE> DO NOTHING
RETURNING <cols>
```

Two notes the implementer needs:

- `VALUES` becomes `SELECT ... WHERE NOT EXISTS`. Bind exactly the same parameters, in the same
  order, from the same `insertParams(delivery)` — do not fork the parameter mapping, or ingest and
  replay rows can drift on which columns they carry.
- **Both guards return the same thing: no row, hence `Optional.empty()`.** The use case does not
  need to know which one fired; ADR-005 §1 maps both to the same 409 with the same reason
  (`LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS` — the constant is already named for both conditions).

**Residual, and state it in the handover.** `NOT EXISTS` reads committed state, so two replays that
run concurrently and are both about to insert are still resolved by `idx_deliveries_live_pair` (one
inserts, one conflicts) — which is correct. The condition that is *not* atomic is a pair going
`DELIVERED` by a concurrent commit in the instant between this statement's `NOT EXISTS` and its
insert. Closing that would need `SERIALIZABLE` or an advisory lock on the pair, and it is not worth
either: the outcome is one extra `PENDING` replay row for a pair that just succeeded, the relay
delivers it, and the subscriber receives one duplicate webhook — which ADR-004's at-least-once
contract already makes the subscriber's own idempotency problem. Document it, do not engineer it.

## Out of Scope

- Any change to `insertIfAbsent`, `insert`, `findLiveByEventAndSubscription` or any other merged
  method on this port or adapter.
- Any change to `idx_deliveries_live_pair` or to any index or migration. The index's predicate is
  correct for its job and is not widened to include `DELIVERED` — doing so would change ingest's
  idempotency semantics (ADR-003 §2) far outside this feature.
- Calling the new method — TASK-008-19.
- Anything tenant-scoped. This port is cross-tenant by design (ADR-007 §5.2, Amendment E1) and the
  new method takes no `TenantId`, exactly like its siblings.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**

Writable now: the port-shape assertion in the style of the merged
`PersistencePortsTest` — `insertReplayIfAbsent` exists, takes `Delivery`, returns
`Optional<Delivery>`, and `insertIfAbsent`'s signature is unchanged.

**DEFERRED — Testcontainers**, specified here so it is not lost, added to
`DeliveryIdempotentInsertTest`:

| # | Case | Expected |
|---|---|---|
| 1 | `insertReplayIfAbsent` for a pair with no existing row | inserts, returns the row |
| 2 | `insertReplayIfAbsent` for a pair whose only row is `DEAD` | inserts, returns the row — this is the ordinary replay |
| 3 | `insertReplayIfAbsent` for a pair with a live (`PENDING`/`QUEUED`/…) row | `Optional.empty()`, no second row |
| 4 | **`insertReplayIfAbsent` for a pair with a `DELIVERED` row** | `Optional.empty()`, no second row. This is the case that is unenforced today |
| 5 | Same pair, `DELIVERED` row present, called twice | `Optional.empty()` both times, still exactly one `DELIVERED` row and no `PENDING` row |
| 6 | `insertIfAbsent` (ingest) for a pair with a `DELIVERED` row | **still inserts**, as it does today — the merged behavior is unchanged, asserted so this task cannot regress it |

Verify with `./gradlew compileJava compileTestJava` plus this task's own port-shape test; **do not
run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] `DeliveryPipelineRepositoryPort` gains exactly one method, `insertReplayIfAbsent(Delivery)`
      returning `Optional<Delivery>`, with one-line javadoc naming both 409 conditions.
- [ ] `insertIfAbsent`, `insert` and every other merged method on the port and the adapter are
      byte-for-byte unchanged.
- [ ] The adapter's new statement shares `INSERT_COLUMNS`, `INSERT_VALUES`,
      `LIVE_STATUS_PREDICATE`, `insertParams` and the `RETURNING` mapper with `insertIfAbsent` — no
      forked column list, no forked parameter map.
- [ ] `'DELIVERED'` reaches SQL as a literal in the statement text, not as a client-supplied value;
      every delivery field stays a bound parameter (A05).
- [ ] No migration, index or constraint is changed.
- [ ] The port-shape unit test is written and passing.
- [ ] The residual race above is written into the handover and into a one-line comment on the
      method.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A10: the condition moves from "checked in a
      separate transaction" to "evaluated by the statement that acts on it").
- [ ] **DEFERRED — Testcontainers:** all six cases above.

## Definition of Done

Port method and adapter statement written and compiling, port-shape unit test passing, the
container cases specified but not written. **Do not run `./gradlew test` or `./gradlew build`** —
verify with `./gradlew compileJava compileTestJava`. **Do not run `git add` or `git commit`.** Set
this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
