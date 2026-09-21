---
id: TASK-004-02
feature: FEAT-004
title: "Delivery domain record: add eventCreatedAt and traceContext"
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-004-02: `Delivery` gains `eventCreatedAt` and `traceContext`

## Feature

FEAT-004

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

This is a **contract** task, not adapter work. It exists because `dba` implements adapters against ports and domain types, and does not edit them (TASK-003-12's own scope boundary). Sequenced ahead of every adapter task.

## Scope

Two new components on the `Delivery` record, per ADR-003 Amendments A3 and A4.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/delivery/Delivery.java`
  - `src/test/java/com/cobre/challenge/domain/model/delivery/DeliveryTest.java`
- Concern: the delivery aggregate's shape.

### The two components

| Component | Type | Nullability | Written by | Read by |
|---|---|---|---|---|
| `eventCreatedAt` | `Instant` | required, never null | the delivery insert, once | the query use case's date filter and keyset (ADR-005 §1) |
| `traceContext` | `Optional<String>` | `Optional.empty()` when absent, never null | the ingest use case | the worker, as ADR-002 §3.1's fallback when the SQS message attribute is absent |

`eventCreatedAt` is the event's own creation timestamp, denormalized (ADR-003 A4). It is **required, not `Optional`**: every row has one, including a replay, which copies the original row's value because it is the same event. Validate it non-null in the compact constructor alongside the existing required components.

`traceContext` is the W3C `traceparent` string. Follow the record's existing convention for optional components exactly: `Objects.requireNonNull(traceContext, "traceContext must not be null (use Optional.empty())")`, the same shape `DeliveryPointer` and `AttemptDeliveryCommand` already use for their `traceparent`.

### The javadoc correction, which is the point of the task and not a comment tweak

The committed class javadoc says persistence-only columns `created_at`, `updated_at` and `trace_context` "are adapter concerns and are not represented here". That grouping was wrong for one of the three and must be corrected, stating the rule it got wrong: **a field belongs in the aggregate when a use case reads or writes it.** `created_at` and `updated_at` are audit metadata no use case reads and they stay out, unchanged. `trace_context` is written by one use case and read by another, so it belongs in. Cite ADR-003 A3.

### Blast radius

Adding record components changes the canonical constructor, so every construction site and every one of the four existing transition methods (`markDelivered`, `markRetrying`, `markDead`, and the queue/claim transition) must carry both new values through unchanged. **Neither new component is ever modified by a transition method** — a transition returns a new `Delivery` with the same `eventCreatedAt` and the same `traceContext`. That is the domain-side half of A4's immutability rule.

Update every construction site in `DeliveryTest` and add assertions for the two properties below. If any other `src/main` file constructs a `Delivery`, fix the call site here; do not add an overloaded constructor or a builder to avoid touching it.

## Out of Scope

- **No port interface change.** That is TASK-004-03, which depends on this task.
- No `created_at` or `updated_at` component. They stay adapter-only, deliberately.
- No new type for either value. `Instant` and `Optional<String>`; no `TraceContext` wrapper, no `EventCreatedAt` value object (YAGNI, and `TenantId`-style wrapper types are ADR-007's deferred feature).
- No change to `DeliveryStatus`, `DeliveryAttempt`, `Subscription`, `NotificationEvent`, or any `port/in` DTO.
- No adapter, no SQL, no migration.
- No retro-edit of TASK-003-04's task file (see `docs/concerns.md`).

## Acceptance Criteria

- [ ] `Delivery` has `Instant eventCreatedAt` (required, validated non-null) and `Optional<String> traceContext` (validated non-null with the "use Optional.empty()" message).
- [ ] The class javadoc no longer lists `trace_context` as persistence-only, states the read-or-written-by-a-use-case rule, cites ADR-003 A3, and still lists `created_at`/`updated_at` as adapter concerns.
- [ ] All four transition methods carry both new components through **unchanged**; no transition method has a parameter for either.
- [ ] Zero framework imports added. `java.time.Instant`, `java.util.Optional`, `java.util.Objects` only.
- [ ] `./gradlew build` compiles with no other `src/main` call site left broken.
- [ ] Tests written and passing: `DeliveryTest` updated at every construction site, plus a test that each transition method preserves `eventCreatedAt` and `traceContext` exactly, and a test that a null `traceContext` throws rather than being coerced to `Optional.empty()`. Plain JUnit, no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. Item 17 (immutability) and Item 55 (`Optional` over `null`) are the operative ones.
- [ ] No new OWASP Top 10:2025 exposure introduced. One caveat to respect: `traceContext` is diagnostic metadata, so do not add a `toString` override that prints it, and do not remove the record's existing handling of sensitive fields if any.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
