---
id: TASK-006-06
feature: FEAT-006
title: RelayBatchClaimer — the claim transaction and the OPEN -> HALF_OPEN promotions
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-01]
date: 2026-09-21
---

# TASK-006-06: `RelayBatchClaimer` — the claim transaction and the circuit promotions

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/RelayBatchClaimer.java` (new)
- Concern: everything a relay cycle does inside one database transaction, and nothing else.

### The contract

```java
@Transactional
List<Delivery> claimAndPromote(int batchLimit, Instant asOf);
```

A `@Component` in `application/usecase`, package-private-friendly in spirit: its only caller is
`DispatchPendingDeliveriesUseCaseImpl` (TASK-006-07). Dependencies: `DeliveryPipelineRepositoryPort`
and `SubscriptionRepositoryPort`, both by interface. It imports no `org.springframework.jdbc.*`
and no `org.springframework.data.*`; `@Transactional` and `@Component` are the only framework
types permitted here, per CLAUDE.md.

### What it does, in order

1. `List<Delivery> claimed = pipelinePort.claimDue(batchLimit, asOf)` — the statement marks the
   batch `QUEUED` and pushes `next_attempt_at` forward 5 minutes in the same statement
   (ADR-002 §2.1).
2. For each **distinct** `subscriptionId` in `claimed`, call
   `subscriptionPort.promoteToHalfOpen(subscriptionId, asOf)`. Pass the **same `asOf`** the claim
   used — one poll cycle, one clock (ADR-002 Amendment C2).
3. Return `claimed`.

### Why it is a separate bean from the use case

Spring's `@Transactional` is proxy-based: a transactional method invoked on `this` from inside the
same bean runs with **no transaction at all**. `claimDue` throws when no transaction is active —
deliberately, because `SKIP LOCKED` outside a transaction acquires no lasting locks and every
relay instance would claim the same batch. Two beans make the boundary structural instead of
remembered. Do not merge this class into the use case, and do not put `@Transactional` on the use
case's `dispatch`.

### Why promotion is called for every distinct subscription, not only the cooled-open ones

`promoteToHalfOpen` carries ADR-006 §1.2's compound guard
(`circuit_state = 'OPEN' AND circuit_opened_at < :as_of - circuit_backoff`). Calling it for a
`CLOSED` or `HALF_OPEN` subscription affects zero rows and writes nothing, so `subscriptions`
stays as cold as ADR-006 §1.2 requires — a zero-row `UPDATE` is not a write. Asking first which
subscriptions are cooled-open would be a second read racing the write it informs, which is exactly
the read-then-write race Amendment C2 exists to remove. **Do not add a lookup method to
`SubscriptionRepositoryPort`.**

### Why promotion is inside the claim transaction

The claim already admitted exactly one row for a cooled-open subscription (TASK-006-01's cap of
`1`). Promoting in the same transaction means a cycle either produces "one probe row `QUEUED` and
the circuit `HALF_OPEN`" or produces neither. Committing the claim and then crashing before the
promotion would leave a `QUEUED` probe against an `OPEN` circuit, which the worker would defer —
recoverable via the pushed clock, but needlessly. One transaction, one outcome. This does not
contradict Amendment C2's "a separate write the relay use case issues": it is a separate write,
issued by the relay, in the same unit of work.

### Return value

Return the claimed list as-is. **Count the promotions but do not return them from this method** —
the counter belongs to the use case (TASK-006-07) and widening the return type to carry a metric
would be a contract shaped by instrumentation. If TASK-006-07 needs the promotion count, expose it
as a second small method or let the use case derive it; decide with the smallest change and say
which you chose.

### Concurrency constraints

No `synchronized`, no `ThreadLocal`, no caching of claimed ids across cycles. The bean is
stateless and shared across virtual threads (ADR-002 §2's pinning rule).

## Out of Scope

- Any SQS call. Nothing in this class touches `NotificationQueuePort`. A network call inside a
  transaction is the one ordering error ADR-001 §1 exists to prevent.
- The due-query SQL — TASK-006-01 owns it.
- `DispatchPendingDeliveriesUseCaseImpl`, the scheduler, and configuration.
- `tripCircuit`, `closeCircuit`, `reopenCircuit`. Those are the worker's writes (ADR-006 §1.2) and
  belong to a later feature.
- Tests beyond whatever you need locally — TASK-006-10 owns the promotion and one-probe
  acceptance test.

## Acceptance Criteria

- [ ] `RelayBatchClaimer` is a separate `@Component` with `@Transactional` on `claimAndPromote`.
- [ ] It depends only on the two `port/out` interfaces; no JDBC, no Spring Data, no SQS type.
- [ ] `promoteToHalfOpen` is called once per **distinct** subscription id in the claimed batch,
      with the same `asOf` as the claim.
- [ ] No `SubscriptionRepositoryPort` method is added.
- [ ] No queue call, no HTTP call, nothing non-deterministic inside the transaction.
- [ ] No `synchronized`, no `ThreadLocal`, no mutable field.
- [ ] `./gradlew build` compiles and existing tests pass.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
