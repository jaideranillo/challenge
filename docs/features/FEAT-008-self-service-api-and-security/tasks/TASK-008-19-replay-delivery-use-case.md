---
id: TASK-008-19
feature: FEAT-008
title: ReplayDeliveryUseCaseImpl — DEAD only, insert a new REPLAY row (named test 4)
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-12, TASK-008-14, TASK-008-16A]
date: 2026-09-21
---

# TASK-008-19: `ReplayDeliveryUseCaseImpl`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/ReplayDeliveryUseCaseImpl.java` (new)
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/RejectionReason.java` (modified — one new constant, see below)
  - `src/test/java/com/cobre/challenge/application/usecase/ReplayDeliveryUseCaseImplTest.java` (new)
- Concern: the replay decision and the new row it inserts.

## The one enum addition this task makes, and why it is not scope creep

`RejectionReason` today has `TARGET_NOT_DEAD` and `LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS`, both
409 conditions. The sealed `ReplayDeliveryResult` has no third variant, so as merged there is no
way for this use case to express **not found** — which ADR-007 §5.5 requires for both a
nonexistent id and another tenant's id on this endpoint.

Add exactly one constant, `TARGET_NOT_FOUND`, and document on it that the controller maps it to
**404** while the other two map to 409. Do **not** add a variant to the sealed
`ReplayDeliveryResult` hierarchy, and do not add any further reason. The alternative — returning
`Optional<ReplayDeliveryResult>` — would change the `port/in` interface ADR-005 §1 fixes at one
method, one command in, one result out.

The 404 reason must be indistinguishable in every observable way from a nonexistent id: same
status, same body, same log level. It exists so the controller can produce 404 without the use
case ever having seen a foreign row.

### Why the two cases are guaranteed identical, and not merely intended to be

The Tech Lead asked for confirmation that "does not exist" and "belongs to another tenant" really
are the same outcome. They are, and the guarantee is structural at three levels:

1. **There is only one call.** Step 1 is `deliveryQueryPort.findById(deliveryId, tenant)` — a
   single, tenant-mandatory method (TASK-008-12). There is no second lookup, no unscoped overload
   to fall back to (ADR-007 §5.2: "there is no `findById(DeliveryId)`", absent rather than
   deprecated), and therefore no way for this use case to learn that an id exists under another
   tenant.
2. **Both cases produce the same value.** The adapter binds `AND client_id = :client_id`
   (TASK-008-15) and the row is additionally filtered by the RLS policy under `challenge_api`
   (TASK-008-08). A foreign row and a nonexistent row both yield an empty result set, hence the
   same `Optional.empty()`. The two are not *mapped* to the same reason; they are the same value
   arriving at the same branch, which is the property ADR-007 §5.5 describes — "there is no 403
   branch to write, because there is no code path that has ever seen the foreign row."
3. **One branch, one construction site.** There must be exactly one `Rejected(TARGET_NOT_FOUND)`
   construction in this class, reached from `Optional.isEmpty()`. Not two branches that happen to
   build equal values: one branch, so no future edit can make them diverge.

The unit test must assert this as an **equality between the two cases**, not as two separate
expectations — see the acceptance criteria. The HTTP half (identical status, body and headers) is
TASK-008-26's; the timing half is not engineered, per ADR-007 §5.5's "in any way that is cheap to
avoid" — both cases run the same single query, so no deliberate work differs between them.

## The fixed order — this is a correctness contract (ADR-007 §5.5, ADR-005 Amendment D1)

```
1. resolve the target TENANT-SCOPED:  deliveryQueryPort.findById(deliveryId, tenant)
   └─ empty -> Rejected(TARGET_NOT_FOUND)      (the controller renders 404)
2. check state:  status == DEAD ?
   └─ no -> Rejected(TARGET_NOT_DEAD)          (the controller renders 409)
3. build the new row and insert it via DeliveryPipelineRepositoryPort.insertReplayIfAbsent
   └─ Optional.empty() -> Rejected(LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS)  (409)
   └─ present          -> Accepted(newDeliveryId, PENDING)                (202)
```

**Step 1 before step 2 is not stylistic.** ADR-007 §5.5: 409 is only ever reachable for a delivery
the caller already owns, because a foreign id is a 404 long before any state check runs. Checking
state first would let a 409-vs-404 difference confirm that someone else's id exists. Order matters
and no task may reorder it.

## The new row (ADR-005 §1, ADR-003 §3)

Same `event_id`, `subscription_id`, `client_id` as the original. `status = PENDING`,
`attempt_count = 0`, `origin = DeliveryOrigin.REPLAY`, `replayed_from = <the original
delivery_id>`. Carry `event_created_at` from the original row (ADR-003 Amendment A4, ADR-005
Amendment D2) — **not** the replay's own timestamp, or the replayed delivery files under the day
someone pressed replay and the client querying the event's real window will not find it.

**The original `DEAD` row is never mutated.** No status change, no counter, no column. It stays
queryable exactly as it was — that is the permanent audit of the original attempt chain, and it is
why the id the client already held still resolves to it.

The result is an **acknowledgment, not an outcome**: `Accepted(newDeliveryId, PENDING)`. The
actual attempt happens later, asynchronously, through the same relay and worker as any other
delivery. Nothing here publishes to the queue, and nothing here waits.

## Why `insertReplayIfAbsent` and not `insert`, and not `insertIfAbsent`

ADR-005 §1 gives replay two 409 conditions: a **live** row already exists for the
`(event_id, subscription_id)` pair, **or** a **`DELIVERED`** one does.
`DeliveryPipelineRepositoryPort.insertReplayIfAbsent` (TASK-008-16A) returns `Optional.empty()` for
either, because both are evaluated by the insert statement itself — the live one by
`idx_deliveries_live_pair`, the `DELIVERED` one by a `WHERE NOT EXISTS` in the same statement.

`insertIfAbsent` is the **ingest** path's method and is deliberately not used here: its rule is
that a `DELIVERED` row frees the pair, which is right for ingest and wrong for replay.

Getting the outcome from the statement rather than from an exception keeps
`org.springframework.dao.*` out of the application layer: catching `DuplicateKeyException` in a use
case would import a Spring type into a layer ADR-007 §5.1 rule 3 keeps framework-free. The outcome
is not swallowed — empty becomes a typed `Rejected` and then a 409.

**Do not add a pre-check query for a `DELIVERED` row.** The statement is the check. A read-then-act
version of the same condition is exactly the two-transaction hole TASK-008-16A exists to close.

## Transactions — two pools, and why that is now a non-issue

The tenant-scoped read runs on the API pool and the insert on the pipeline pool, so they cannot
share a transaction. **Nothing in this use case depends on them sharing one**, and the reasoning is
worth stating because it is what makes the split safe rather than merely tolerated:

- **The target's state cannot go stale.** Step 2 accepts only `DEAD`, and `DEAD` is terminal —
  `DeliveryStatus.DEAD.legalTargets()` is empty, so no code path moves a row out of it. A target
  that was `DEAD` at the read is still `DEAD` at the insert, always.
- **The pair's state is not read at all.** Both remaining conditions — a live row, a `DELIVERED`
  row — are evaluated **inside** `insertReplayIfAbsent`'s statement, against committed state, at
  insert time. There is no earlier read of them to go stale between transactions.
- **A crash between the two leaves the original `DEAD` row untouched**, which is the same state as
  never having called.

So there is no correctness argument for one transaction, and there is a positive architectural
argument for two: the replay row is a pipeline row, written through the one insert shape the ingest
gateway also uses, which is what ADR-005 §1's "reuses one code path" means. Giving `challenge_api`
an `INSERT` grant and a `WITH CHECK` policy would not remove the two-transaction split — it would
require a **second** delivery-insert adapter on the API pool, duplicating the column list, the
`ON CONFLICT` inference predicate and the replay guard, for a race that no longer exists. That is
the real reason, recorded in FEAT-008 feature.md, and it is not "fewer permissions".

Do not attempt a distributed or chained transaction, and do not move the insert onto the API pool.

Annotate the read step with `@Transactional(transactionManager = "apiTransactionManager",
readOnly = true)` — extract it to its own method or collaborator if the annotation cannot
otherwise apply cleanly, but do not put one `@Transactional` across both pools.

## Named test 4 — the user's wording, verbatim

> **Replaying a non-DEAD delivery returns 409.**

This task owns the unit-level half: every non-`DEAD` status yields
`Rejected(TARGET_NOT_DEAD)`. TASK-008-28 owns the HTTP 409 end to end.

## Out of Scope

- The controller, the `Idempotency-Key` header, and the 202/409/404 mapping — TASK-008-26.
- The `insertReplayIfAbsent` port method and its SQL — TASK-008-16A. This task **calls** it.
- The idempotency guard — TASK-008-27.
- Any queue publish. Replay deliberately re-enters through the relay's due query, not the queue
  (ADR-005 §1), and publishing here would create a second code path and a way to lose a replay.
- Any mutation of the original row.
- Any `RejectionReason` value beyond the single `TARGET_NOT_FOUND` this task adds. The other two
  are the two ADR-005 §1 names and stay as they are.
- `DeliveryOrigin.RECOVERED` and the internal recovery action (ADR-003 §1.1). Not a public
  endpoint, not this feature.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
The decision logic is entirely mockable: this task writes the **unit-level half of named test 4**
(every non-`DEAD` status yields `Rejected(TARGET_NOT_DEAD)`) and the `insertIfAbsent`-empty path.

`DEFERRED — Testcontainers`, specified here so they are not lost and owned by TASK-008-28: the
HTTP 409 end to end, and **named test 5 — two rapid replays create one delivery** — which depends
on `idx_deliveries_live_pair` and can only be proven against a real database. A mocked port
returning empty on the second call is *not* that test and must not be presented as it.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] Implements `ReplayDeliveryUseCase`; one public method returning the sealed
      `ReplayDeliveryResult`.
- [ ] The tenant-scoped resolve runs **first**; an empty result returns the not-found outcome and
      **no state check and no insert run**.
- [ ] **The two not-found cases are asserted equal, in one test method.** Case A: the mocked query
      port returns `Optional.empty()` for an id that exists for no tenant. Case B: it returns
      `Optional.empty()` for an id that the fixture holds for a *different* tenant (the mock models
      the tenant-scoped port: it answers by `(id, tenant)` and has a row for `(id, OTHER_TENANT)`).
      The test asserts the **two results are equal to each other**, not merely that each is
      `Rejected(TARGET_NOT_FOUND)`, and asserts that the pipeline port received no call in either
      case. One assertion of equivalence, so a future edit cannot make the two diverge silently.
- [ ] Exactly one `Rejected(TARGET_NOT_FOUND)` is constructed in the class, reached from a single
      `Optional.isEmpty()` branch.
- [ ] The class contains no branch, log statement or metric that distinguishes the two cases.
- [ ] Only `DEAD` is accepted. A unit test enumerates **every** other `DeliveryStatus` value —
      driven off `DeliveryStatus.values()`, so a future status cannot silently become replayable —
      and asserts `Rejected(TARGET_NOT_DEAD)` for each. `FAILED` is explicitly among them
      (ADR-004 §1: `DEAD`, not `FAILED`).
- [ ] The inserted row has `status = PENDING`, `attempt_count = 0`, `origin = REPLAY`,
      `replayed_from` = the original id, the original's `event_id`, `subscription_id`, `client_id`
      and `event_created_at`.
- [ ] The original row is not mutated — asserted by the port mock receiving no write for it.
- [ ] The insert goes through `insertReplayIfAbsent`, never `insertIfAbsent` or `insert`, and
      returning empty yields `Rejected(LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS)`.
- [ ] No query for an existing `DELIVERED` row is issued from this class — the condition lives in
      the insert statement (TASK-008-16A).
- [ ] `Accepted` carries the **new** row's id and `PENDING`.
- [ ] Nothing publishes to a queue; nothing waits for an attempt.
- [ ] No `org.springframework.dao.*`, `org.springframework.security.*` or `org.springframework.jdbc.*`
      import appears in this class.
- [ ] No single `@Transactional` spans both pools.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01: the resolve-then-check order is the
      control).

## Definition of Done

Code and tests written, tests passing locally. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Implemented (backend-engineer, 2026-09-21)

TASK-008-16A (`insertReplayIfAbsent`) reached Ready for Review, unblocking this task. Implemented
as specified:

- `ReplayDeliveryUseCaseImpl` — resolves the target tenant-scoped
  (`DeliveryQueryRepositoryPort.findById`, isolated in a package-private
  `@Transactional(transactionManager = "apiTransactionManager", readOnly = true)` method so it
  does not share a transaction with the pipeline-pool insert), rejects `TARGET_NOT_FOUND` on
  empty, rejects `TARGET_NOT_DEAD` on any non-`DEAD` status, otherwise builds the new `PENDING`/
  `REPLAY`/`attempt_count=0` row (carrying `event_id`, `subscription_id`, `client_id`,
  `event_created_at` from the original, `replayed_from` = original id) and inserts it via
  `insertReplayIfAbsent`, mapping empty to `Rejected(LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS)` and
  present to `Accepted(newDeliveryId, PENDING)`.
- `RejectionReason` — added `TARGET_NOT_FOUND` only, documented as the 404 case; the other two
  constants are unchanged.
- `ReplayDeliveryUseCaseImplTest` — fake `DeliveryQueryRepositoryPort` and
  `DeliveryPipelineRepositoryPort`, no Spring context. Covers: DEAD target accepted with correct
  new-row fields; `insertReplayIfAbsent` empty -> `LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS`; the
  named equivalence test asserting a nonexistent id and another tenant's id produce the identical
  `Rejected(TARGET_NOT_FOUND)` and that the pipeline port is never called in either case; every
  non-`DEAD` `DeliveryStatus.values()` entry (`FAILED` included) rejected as `TARGET_NOT_DEAD`
  with the pipeline port never called.

Verified with `./gradlew compileJava compileTestJava` and
`./gradlew test --tests "com.cobre.challenge.application.usecase.ReplayDeliveryUseCaseImplTest"`
(4/4 passing). Did not run the full `test` or `build` task, per phase rule.

No new port/contract mismatch found: `RejectionReason`'s only other consumer
(`QueryGetReplayUseCasePortsTest`) does not switch exhaustively over the enum, so the new constant
needed no follow-up edit there. Nothing added to `docs/concerns.md` for this task.

### Blocked (backend-engineer, 2026-09-21) — resolved, kept for history

Not started. This task `depends_on: [TASK-008-12, TASK-008-14, TASK-008-16A]`. TASK-008-16A
(`insertReplayIfAbsent`) is still `status: Not Started` — verified by reading
`DeliveryPipelineRepositoryPort.java` and `DeliveryPipelineJdbcRepository.java` directly: neither
declares `insertReplayIfAbsent`, only the existing `insertIfAbsent` (ingest semantics: a
`DELIVERED` row frees the pair) and `insert`.

Per this task's own "Why `insertReplayIfAbsent` and not `insert`, and not `insertIfAbsent`"
section, implementing against `insertIfAbsent` instead would be wrong, not merely premature: that
method's rule ("a `DELIVERED` row frees the pair") is the ingest path's semantics and would silently
under-enforce ADR-005 §1's second 409 condition (a `DELIVERED` row already exists for the pair).
Implementing against it now would produce code that compiles and looks done but is functionally
wrong, and TASK-008-16A explicitly forbids adding the `DELIVERED` condition to the shared method.

Stopping here rather than working around it. No file in this task's scope
(`ReplayDeliveryUseCaseImpl.java`, `RejectionReason.java`, `ReplayDeliveryUseCaseImplTest.java`) has
been created. Once `dba` completes TASK-008-16A and it reaches `Ready for Review`, this task can
proceed unchanged as specified.
