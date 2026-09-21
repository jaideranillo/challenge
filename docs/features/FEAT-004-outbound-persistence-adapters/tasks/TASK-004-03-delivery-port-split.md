---
id: TASK-004-03
feature: FEAT-004
title: "Split DeliveryRepositoryPort along the tenant boundary and replace transitionStatus with intent-revealing operations"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-004-02]
date: 2026-09-20
---

# TASK-004-03: the delivery port split and its intent-revealing operations

## Feature

FEAT-004

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

A **contract** task, sequenced ahead of every `dba` adapter task that implements against these interfaces.

## Scope

Replace `DeliveryRepositoryPort` with two interfaces and replace its generic `transitionStatus` with named operations. Implements ADR-003 Amendments A1 and A2, ADR-007 Amendment E1, ADR-002 Amendment C1.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryPipelineRepositoryPort.java` (new)
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryQueryRepositoryPort.java` (new)
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryRepositoryPort.java` (deleted)
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java` (amended)
- Concern: the delivery port contract.

Four files, one of them a deletion and one a test. Above the ~3-file guideline by one, and deliberately so: splitting an interface and defining the operations that live on each half is a single contract decision, and reviewing the halves apart from each other would hide the boundary that is the whole point.

### `DeliveryPipelineRepositoryPort` — cross-tenant, no `client_id` on any method

Every transition is **one atomic conditional `UPDATE`** returning `boolean` derived from the affected row count. **Zero rows affected is a normal outcome and must never throw** (ADR-002 §2.2 step 2, and TASK-003-12's existing convention). Javadoc each method with its guard and the columns it writes.

| Method | Guard | Writes beyond `status` and `updated_at` | Source |
|---|---|---|---|
| `Delivery insert(Delivery delivery)` | partial unique index | every column, incl. `trace_context`, `event_created_at` | ADR-003 §2, A3, A4 |
| `Optional<Delivery> findById(UUID deliveryId)` | - | read | ADR-002 §2.2 steps 5-6 |
| `boolean claimForProcessing(UUID deliveryId, Instant now)` | `status = 'QUEUED'` | - | ADR-002 §2.2 step 1 |
| `boolean markDelivered(UUID deliveryId, Instant deliveredAt)` | `status = 'PROCESSING'` | `delivered_at`, `next_attempt_at = NULL` | ADR-003 §1.1 |
| `boolean scheduleRetry(UUID deliveryId, Instant nextAttemptAt, String lastError, Instant now)` | `status = 'PROCESSING'` | `attempt_count + 1`, `next_attempt_at`, `last_error` | ADR-003 §1.1, ADR-004 §1 |
| `boolean markDead(UUID deliveryId, String lastError, Instant now)` | `status = 'PROCESSING'` | `next_attempt_at = NULL`, `last_error` | ADR-003 §1.1 |
| `boolean markFailed(UUID deliveryId, String lastError, Instant now)` | `status IN ('QUEUED', 'PROCESSING')` | `last_error` | ADR-003 §1.1 |
| `boolean deferDelivery(UUID deliveryId, Instant nextAttemptAt)` | `status = 'QUEUED'` | `next_attempt_at` only; status unchanged | ADR-002 §2.2 steps 3-4 |
| `List<Delivery> claimDue(int batchLimit, Instant asOf)` | `FOR UPDATE ... SKIP LOCKED` | batch claim | ADR-002 §2.1 |

`insert`, `claimDue` and `findByIdForTenant`-free signatures carry over from `DeliveryRepositoryPort` unchanged where they already existed; only the transitions are new.

Four points the javadoc must state, because an implementer cannot infer them:

1. **`claimForProcessing` is the safety-critical one.** It is the only thing preventing a double POST (ADR-003's Q3 resolution). It is not merely `QUEUED -> PROCESSING` with a guard bolted on; the guard *is* the operation.
2. **`markFailed`'s guard is a status set, not a single expected state.** ADR-003 §1's machine admits `QUEUED -> FAILED` and `PROCESSING -> FAILED` (see `DeliveryStatus.legalTargets()`) because a message reaches the DLQ from either and the consumer does not know which. It is still a guard: the three terminal states are excluded, so a late DLQ message cannot overwrite a row that already reached `DELIVERED`.
3. **`deferDelivery` is defined by what it must not do.** No status change, **no `attempt_count` increment, no `last_error`, and no `delivery_attempts` row**, because no attempt occurred (ADR-002 §2.2 step 3 says this in prose; the signature is what makes it enforceable). Its two-parameter arity is fixed by the directive, so `updated_at` comes from the database clock in the adapter — logged in `docs/concerns.md`, do not "fix" it by adding a third parameter.
4. **`insert` and `findById` are the only methods that touch `trace_context` and `event_created_at`**, and both arrive on the `Delivery` aggregate (TASK-004-02), so no method needs a separate parameter for either. No transition method may accept or write either column.

`findById(UUID)` takes no tenant **deliberately**, and its javadoc must say why, because it looks like the thing ADR-007 §5.2 forbids. §5.2's "there is no `findById(DeliveryId)`" governs the client-facing port and still holds there. The worker must load the row it just claimed for `event_id`, `subscription_id`, the authoritative `attempt_count` and `trace_context`, and it runs with no principal; §5.2's own carve-out for cross-tenant pipeline ports covers it. Cite ADR-007 Amendment E1.

### `DeliveryQueryRepositoryPort` — tenant mandatory on every method

| Method |
|---|
| `Optional<Delivery> findById(UUID deliveryId, String clientId)` |
| `DeliveryPage findPage(String clientId, Optional<Instant> eventCreatedFrom, Optional<Instant> eventCreatedTo, Optional<DeliveryStatus> status, Optional<String> cursor, int limit)` |

`findPage`'s date parameters are **renamed** from the committed `createdFrom`/`createdTo` to `eventCreatedFrom`/`eventCreatedTo`, so a call site cannot confuse the event's timestamp with the delivery row's own (ADR-005 D2). Javadoc must state that both bound `deliveries.event_created_at` and that the keyset tuple is `(event_created_at, delivery_id)`.

**No unscoped overload on this interface, ever.** Javadoc it as a class-level invariant, citing ADR-007 §5.2 and E1: this is the port a client-facing use case injects, and the reason the pipeline's unscoped read is safe is that it is not reachable from here.

Both interfaces keep `String clientId` rather than a `TenantId` type — deferred to ADR-007's feature, already logged in `docs/concerns.md`.

## Out of Scope

- **No adapter implementation.** Interfaces only. The JDBC classes are `dba`'s, TASK-004-06 onward.
- No `TenantId`, no `DeliveryId`, no wrapper types.
- No change to `DeliveryAttemptRepositoryPort`, which stays a single cross-tenant interface.
- No change to `SubscriptionRepositoryPort` — that is TASK-004-04, which depends on this task.
- No change to `DeliveryPage` or any `port/in` interface or DTO.
- No use case class and no `@Transactional`.
- No ArchUnit rule (ADR-007's feature; and note in passing that a blanket "every port method takes a tenant" rule would be wrong after this split — recorded in ADR-007 E1, not implemented here).
- No retro-edit of TASK-003-12's task file (see `docs/concerns.md`).

## Acceptance Criteria

- [ ] `DeliveryRepositoryPort.java` is deleted; the two new interfaces exist with exactly the methods tabled above and no others.
- [ ] Every conditional method returns `boolean`, not `void` and not `int`, and no javadoc suggests throwing on zero rows.
- [ ] `markFailed`'s javadoc states the status-set guard and why terminal states are excluded.
- [ ] `deferDelivery`'s javadoc enumerates the three things it must not touch.
- [ ] No transition method has a `traceContext` or `eventCreatedAt` parameter.
- [ ] `findById(UUID)`'s javadoc justifies the absent tenant and cites ADR-007 E1; `DeliveryQueryRepositoryPort` has no unscoped overload and says so at class level.
- [ ] `findPage`'s date parameters are named `eventCreatedFrom`/`eventCreatedTo` and the javadoc names the keyset tuple.
- [ ] Zero framework imports in either file. JDK types and `domain/model` only; nothing from `org.springframework.*`.
- [ ] Every method returns `Optional`, a collection, `boolean`, or a domain type — never `null` (Effective Java Items 54-55).
- [ ] Tests written and passing: `PersistencePortsTest` amended to assert the new shape by reflection in the same style it already uses for the old one — both interfaces exist, no method on `DeliveryPipelineRepositoryPort` has a `clientId` parameter, **every** method on `DeliveryQueryRepositoryPort` does, no return type is `void` for a conditional operation, and neither interface imports a framework type. Plain JUnit, no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. ISP is the operative one and is the reason this task exists; also Item 64 (small interfaces) and Item 17.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** the split reduces exposure by making the tenant boundary a type distinction rather than a convention; the acceptance criterion above is the control. **A10:** the `boolean` returns are what keep ADR-002 §2.2 step 2's benign duplicate from becoming a crash loop.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
