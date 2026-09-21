---
id: TASK-003-04
feature: FEAT-003
title: Delivery aggregate
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-02]
date: 2026-09-20
---

# TASK-003-04: Delivery aggregate

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The `Delivery` aggregate: an immutable record carrying the row's domain-relevant state, with transition operations that delegate to `DeliveryStatus` and produce a new instance.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/delivery/Delivery.java` (new)
  - `src/test/java/com/cobre/challenge/domain/model/delivery/DeliveryTest.java` (new)
- Concern: the delivery aggregate and its transition operation.

Components, mapped from ADR-003 §3's `deliveries` table, domain-relevant columns only:
`deliveryId` (UUID), `eventId` (String), `subscriptionId` (UUID), `clientId` (String), `status`, `origin`, `replayedFrom` (`Optional<UUID>` or a nullable field exposed through an `Optional` accessor), `attemptCount` (int), `nextAttemptAt` (`Optional<Instant>`), `lastError` (`Optional<String>`), `deliveredAt` (`Optional<Instant>`).

**Persistence-only columns do not belong here**: `created_at`, `updated_at` and `trace_context` are adapter concerns (ADR-003 §3) and are not components of this record.

Operations, each returning a new `Delivery`:
- `Delivery transitionTo(DeliveryStatus target)` — delegates to `status.transitionTo(target)`, so an illegal transition throws `IllegalDeliveryTransitionException` and nothing is silently swallowed.
- `Delivery markDelivered(Instant at)` — transitions to `DELIVERED`, sets `deliveredAt`, clears `nextAttemptAt`.
- `Delivery markRetrying(Instant nextAttemptAt, String lastError)` — transitions to `RETRYING`, increments `attemptCount`, sets both fields (ADR-003 §1.1's "Worker (retryable failure)" row).
- `Delivery markDead(String lastError)` — transitions to `DEAD`, clears `nextAttemptAt` to empty (ADR-003 §1.1's "budget exhausted / non-retryable" row).

Compact constructor validates: no `null` component, `attemptCount >= 0`, and `deliveredAt` present only when `status == DELIVERED`.

## Out of Scope

- No `NotificationEvent`, `Subscription` or `DeliveryAttempt` record — TASK-003-05.
- No retry-schedule arithmetic. `markRetrying` **receives** the next attempt time; computing it is `RetryPolicy`'s job (TASK-003-08) and the use case wires the two together later.
- No classification logic — TASK-003-06.
- No factory that reads from a database row, no Spring Data JDBC annotations, no `@Table`, no `@Id`.
- No `markFailed` convenience: `FAILED` is written by the DLQ consumer only (ADR-003 §1.1) and `transitionTo(FAILED)` already expresses it. Do not add a second way to do one thing.

## Acceptance Criteria

- [ ] `Delivery` is a `record`, fully immutable, with no setter and no mutable component exposed.
- [ ] Optional-valued accessors return `Optional`, never `null` (Effective Java Items 54-55).
- [ ] Every named operation returns a new instance; the receiver is never mutated.
- [ ] An illegal transition through any of the four operations propagates `IllegalDeliveryTransitionException` — asserted in the test.
- [ ] `markRetrying` increments `attemptCount` by exactly one; `markDelivered` and `markDead` leave it unchanged.
- [ ] No import outside `java.*`.
- [ ] Tests written and passing: plain JUnit, no Spring context, covering each operation's happy path and its illegal-source-state throw.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
