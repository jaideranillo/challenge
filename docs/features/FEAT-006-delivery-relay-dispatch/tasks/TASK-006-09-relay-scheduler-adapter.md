---
id: TASK-006-09
feature: FEAT-006
title: DeliveryRelayScheduler — the @Scheduled inbound adapter
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-07, TASK-006-08]
date: 2026-09-21
---

# TASK-006-09: `DeliveryRelayScheduler` — the `@Scheduled` inbound adapter

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/DeliveryRelayScheduler.java` (new)
- Concern: turn a clock tick into one `DispatchPendingDeliveriesCommand`.

### What it is

An **inbound adapter**, in `adapter/in`, with the same discipline as a controller: it does
orchestration only — build the command, call the use case, handle the transport-level failure
mode. **No business logic.** No claim, no publish, no decision about what is due, no retry policy.
If you find yourself writing an `if` about a delivery in this class, it belongs in the use case.

```java
@Component
class DeliveryRelayScheduler {
    @Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")
    void pollOnce() { ... }
}
```

- `fixedDelay`, not `fixedRate` — ADR-002 §2.1 says `fixedDelay`, and it is the right one:
  `fixedRate` would overlap cycles when a poll runs long and put two claims in flight from one
  instance. (Overlap is *safe* — `SKIP LOCKED` guarantees disjoint batches — but it is not what
  the ADR specifies and it makes load unpredictable.)
- `batchLimit` comes from `RelayProperties`; `asOf` is `Instant.now()`, captured once per cycle and
  passed down, so every predicate in that cycle evaluates against one clock.

### The one failure-mode requirement

**Catch `Throwable` around the whole cycle, log at warn, and return normally.** An exception
escaping a `@Scheduled` method with `fixedDelay` is not merely a lost cycle — depending on the
scheduler it can cancel the task outright, and a relay that has silently stopped is the single
worst failure mode in this system: the relay is the only mechanism that must work for delivery to
happen at all (ADR-002 §2.1). This is OWASP A10 in its most literal form, and it is why this class
exists at all rather than putting `@Scheduled` on the use case.

Log the failure at warn with the exception, no payload and no URL (ADR-002 §3.1). Do not count a
new meter here — TASK-006-07 already counts what happened inside the cycle, and a thrown cycle is
visible as a gap in `notification.relay.claimed`.

### What it must not become

- **No HTTP surface.** No controller, no actuator endpoint, no `@RequestMapping`, no manual
  trigger reachable from outside the process. The relay's only trigger is this clock tick
  (FEAT-006 Security Impact).
- **No `DeliveryQueryRepositoryPort` injection**, and no persistence port at all. This class
  depends on `DispatchPendingDeliveriesUseCase` and `RelayProperties`, and on nothing else.
- **No `synchronized`, no overlap guard, no lock.** Concurrent cycles across instances are what
  `SKIP LOCKED` is for (ADR-002 §2), and a `synchronized` guard around a method that blocks on
  JDBC and SQS would pin the carrier of a virtual thread for the whole cycle — the exact pinning
  pattern ADR-002 §2 names.

## Out of Scope

- The use case, the claimer, the due-query, the SQS adapter.
- `RelayProperties` and `RelaySchedulingConfig` — TASK-006-08 owns them; inject and use them.
- Acceptance tests — TASK-006-10 and TASK-006-11.
- Any dead-letter, alerting, or health-indicator work.

## Acceptance Criteria

- [ ] `DeliveryRelayScheduler` lives under `adapter/in/scheduling` and depends only on
      `DispatchPendingDeliveriesUseCase` and `RelayProperties`.
- [ ] `@Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")`, never `fixedRate`.
- [ ] `asOf` is captured once per cycle.
- [ ] `Throwable` is caught and the method returns normally; a test proves a throwing use case
      does not stop the scheduler (call `pollOnce()` directly twice with a use case stub that
      throws on the first call — a unit-level test is appropriate here, since the concern is the
      adapter's own error handling, not a database behaviour).
- [ ] No HTTP surface, no persistence port, no `synchronized`, no overlap guard.
- [ ] Nothing containing payload, target URL, or secret material is logged.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
