---
id: TASK-007-10
feature: FEAT-007
title: BulkheadPort and the Resilience4j per-subscription bulkhead adapter
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-01, TASK-007-02]
date: 2026-09-21
---

# TASK-007-10: `BulkheadPort` and `Resilience4jBulkheadAdapter`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/resilience/BulkheadPort.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/resilience/Resilience4jBulkheadAdapter.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/resilience/Resilience4jBulkheadAdapterTest.java` (new)
- Concern: ADR-006 §1.3's per-subscription permit set, behind a port so the use case never imports
  Resilience4j.

### Contract

```java
// application/port/out/resilience/BulkheadPort
/** Acquires one permit for this subscription, waiting at most timeout. False means deferral. */
boolean tryAcquire(UUID subscriptionId, int maxConcurrency, Duration timeout);
/** Releases the permit acquired by this thread. Must be called from a finally block. */
void release(UUID subscriptionId);
```

- `maxConcurrency` comes from the already-loaded `subscriptions` row (default 10). The adapter
  **never reads the database** and holds no repository.
- `timeout` is `challenge.worker.bulkhead.acquire-timeout`, default 2s (ADR-006 §1.3). The caller
  passes it so the value is visible at the decision site, not buried in a registry.
- `false` is a normal, expected outcome — the deferral path, not an error. Never throw for it.

### Adapter

- One `io.github.resilience4j.bulkhead.Bulkhead` instance per `subscription_id`, held in a
  `ConcurrentHashMap` and created with `computeIfAbsent`. **The mapping function must not block**
  — building a `Bulkhead` is cheap and local, which is the only reason `computeIfAbsent` is safe
  here (a blocking mapping function inside a `ConcurrentHashMap` bin lock is a virtual-thread
  pinning hazard).
- Configure `maxConcurrentCalls = maxConcurrency` and `maxWaitDuration = timeout`.
  If an existing instance's configured concurrency differs from the value passed in (the row's
  `max_concurrency` changed since the instance was created), replace the instance rather than
  silently honoring the stale limit. Keep that replacement lock-free (`compute`), not
  `synchronized`.
- **No `synchronized` anywhere in this class.** `Bulkhead.tryAcquirePermission(Duration)` parks
  the virtual thread, which is exactly what we want; wrapping it in a monitor would pin the
  carrier for the whole wait.
- Instances are per-pod and in-memory by design (ADR-006 §1.3: "in-process semaphore"). Nothing
  here is shared across replicas and nothing is persisted.
- No unbounded growth concern is in scope: one small object per subscription is the intended
  footprint. Do not add an eviction policy (YAGNI); note the memory shape in the handover if you
  think it warrants a later look.

## Out of Scope

- The deferral write itself (`deferDelivery` with jitter) — TASK-007-14 owns it.
- The circuit breaker (TASK-007-11).
- Metrics. The bulkhead-deferral counter (ADR-002 §3) is incremented by the use case, not here.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Test the adapter directly — Resilience4j is an in-process library, so this is a fast unit
test, not an integration test.

Required unit scenarios:
- with `maxConcurrency = 1`, a first `tryAcquire` succeeds and a second (from another thread,
  before any release) returns `false` after roughly the timeout, without throwing
- after `release`, a subsequent `tryAcquire` succeeds again
- two different `subscriptionId`s do not contend with each other
- calling `tryAcquire` with a changed `maxConcurrency` for an existing subscription honors the new
  value
- no test needs `Thread.sleep` longer than a short timeout — pass a small `Duration` in the test
  rather than the production 2s

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] The port lives in `application/port/out/resilience` and carries no Resilience4j type.
- [ ] No Resilience4j import exists outside `adapter/out/resilience`.
- [ ] One bulkhead instance per subscription, created lock-free, with a non-blocking mapping function.
- [ ] `tryAcquire` returns `false` on timeout and never throws.
- [ ] `release` is safe to call when a permit was acquired and is documented as `finally`-only.
- [ ] No `synchronized`, no `ThreadLocal`, no database access, no logging of a subscription's data.
- [ ] Every unit scenario listed above is covered by a plain JUnit test.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
