---
id: TASK-005-04
feature: FEAT-005
title: "DeliveryPipelineRepositoryPort: insertIfAbsent and findLiveByEventAndSubscription"
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-005-04: the idempotency-conflict return signal

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryPipelineRepositoryPort.java` (add two methods)
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java` (extend)
- Concern: making ADR-003 §2's "treats that as success" expressible.

```java
Optional<Delivery> insertIfAbsent(Delivery delivery);

Optional<Delivery> findLiveByEventAndSubscription(String eventId, UUID subscriptionId);
```

The problem being fixed: §2 says a conflict on the partial unique index "is treated as success (returning the existing delivery) rather than an error", but `insert` returns `Delivery` and can only report a conflict by throwing — the one thing §2 forbids. See ADR-003 Amendment A5.

Contract, in javadoc:

1. **`Optional.empty()` from `insertIfAbsent` means a live row already exists for `(event_id, subscription_id)`** — the idempotent-replay outcome, not a failure. Say this in the first line, because an empty `Optional` from a method named "insert" is surprising unless the reason is immediate.
2. **"Live" is exactly `idx_deliveries_live_pair`'s predicate**: `status NOT IN ('DELIVERED', 'DEAD', 'FAILED')`. Cite the index by name. Both methods must use the same definition of live, or a conflict could return no row on the follow-up read.
3. **`insert` is kept, unchanged, and is not deprecated.** `POST /replay` and internal recovery (ADR-005 §1) must fail loudly when the pair is still live; ingest is the only caller that wants the conflict swallowed. Two callers, two intents, two methods — do not collapse them into one method with a flag.
4. **`findLiveByEventAndSubscription` returns at most one row**, guaranteed by the unique partial index, which is why the return is `Optional` and not `List`.
5. Both are cross-tenant like every other method on this port; no `clientId` parameter.

## Out of Scope

- Any adapter change. TASK-005-08 implements both against Postgres.
- `DeliveryQueryRepositoryPort` — the client-facing port gains nothing here, and must not.
- Any change to `insert`, to any conditional write, to `claimDue`, or to the `Delivery` record.
- Deprecating or removing `insert`.

## Acceptance Criteria

- [ ] Two methods added; every existing method byte-identical.
- [ ] Javadoc on `insertIfAbsent` leads with what `Optional.empty()` means.
- [ ] Both javadocs state the live predicate explicitly and name `idx_deliveries_live_pair`.
- [ ] Javadoc records why `insert` remains, naming replay and recovery as its callers.
- [ ] Zero framework imports in the file, still.
- [ ] Tests written and passing: `PersistencePortsTest` extended for both signatures.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** the point of this change is that the idempotent path stops relying on an exception; the contract must make a conflict a return value.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

Note (2026-09-20): full-module `compileJava` fails until TASK-005-08 lands, because
`DeliveryPipelineJdbcRepository` does not yet override the two new methods. Confirmed with the
coordinator this is expected port-before-adapter sequencing (TASK-005-08 declares
`depends_on: [TASK-005-04]`), not a defect in this task. Port interface and
`PersistencePortsTest` changes are complete per scope.
