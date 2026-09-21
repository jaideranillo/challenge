---
id: TASK-005-14
feature: FEAT-005
title: "IngestPublishDispatcher: after-commit, off the request thread, best-effort, counted"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-11, TASK-005-13]
date: 2026-09-20
---

# TASK-005-14: the post-commit publish

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/IngestPublishDispatcher.java` (new)
  - `src/main/java/com/cobre/challenge/application/usecase/RegisterNotificationEventUseCaseImpl.java` (one call added)
  - `src/test/java/com/cobre/challenge/application/usecase/IngestPublishDispatcherTest.java` (new)
- Concern: ADR-002 §1.1 step 5 and Amendment C3 — the one place a design mistake could let the queue fail the request.

### What it does

For each delivery id the use case is about to return, and only for ids whose row this call actually inserted:

1. **Register an after-commit callback** — `TransactionSynchronizationManager.registerSynchronization(...)`, `afterCommit` only. Never `afterCompletion` with a status check, never `beforeCommit`: publishing a pointer to a row that may still roll back is the one ordering error ADR-001 §1 exists to prevent.
2. **In that callback, submit** the `NotificationQueuePort.publish(pointer)` to a virtual-thread executor (`Executors.newVirtualThreadPerTaskExecutor()`) and return. The request thread must not perform the `SendMessage`, or a slow SQS becomes producer-visible latency on a path whose entire contract is `202` and go.
3. **Catch every `Throwable` inside the submitted task.** Log at warn, by `delivery_id` only, and increment the counter. Nothing propagates out of the task, and the callback itself cannot throw.
4. **Count it:** a Micrometer counter `notification.ingest.publish.failed`. **No `client_id` and no `subscription_id` tag** — ADR-002 Q8 rejects both as unbounded label cardinality. A publish failure is invisible to the caller by design; the counter is the only reason it is not also invisible to operations.

The `DeliveryPointer` is built from the inserted `Delivery`: `deliveryId`, `subscriptionId`, `attemptHint = 0`, `traceparent` = the delivery's `traceContext`.

**Only newly inserted rows are published.** A replay whose `insertIfAbsent` returned `empty()` found a live row that the relay is already responsible for; re-publishing it would put a second pointer on the queue for one delivery, which the worker's `claimForProcessing` guard survives but which is pure waste. State this in a comment.

**No `synchronized` on this path.** A `synchronized` block around the submission or around the publish pins a carrier thread across a network call.

Tests: plain JUnit with a fake queue port and a synchronous executor — assert that a failing publish does not propagate, that the counter increments, and that no payload field appears in the logged message. The database-backed, real-SQS proof is TASK-005-17.

### The one injectable seam

Make the executor a constructor parameter (defaulted to the virtual-thread executor by the Spring configuration) so a test can pass a same-thread executor and assert deterministically. Do not add a "publish synchronously" flag or a profile check — a seam for tests is not a runtime mode.

## Out of Scope

- Retry, backoff, or a persistent outbox around the failed publish. The relay is the guaranteed path (ADR-001 §1, §2); adding a second recovery mechanism here would duplicate it.
- Any change to the transactional core's flow, its ports, or its result. The only edit to the use case is the call that hands ids to the dispatcher.
- Alerting rules or dashboards for the new counter.
- `publishBatch`. Ingest publishes one pointer per delivery.
- The controller and security.

## Acceptance Criteria

- [ ] The publish is registered as an `afterCommit` synchronization; `beforeCommit` and `afterCompletion` appear nowhere.
- [ ] The publish executes on a virtual-thread executor, not the request thread.
- [ ] Every `Throwable` is caught inside the submitted task; nothing propagates to the caller under any failure.
- [ ] `notification.ingest.publish.failed` is incremented on failure, with no `client_id` or `subscription_id` tag.
- [ ] The failure log is at warn, names `delivery_id`, and contains no event content.
- [ ] Only deliveries inserted by this call are published, with a comment saying why.
- [ ] No `synchronized`, no `ThreadLocal`, no reactive type.
- [ ] The executor is constructor-injected; no runtime flag or profile switch changes the publish mode.
- [ ] Tests written and passing: failure does not propagate, counter increments, no payload in the log message.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative category: this path must fail open with respect to the request and closed with respect to ordering. **A09:** the counter is what keeps a silent failure observable.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
