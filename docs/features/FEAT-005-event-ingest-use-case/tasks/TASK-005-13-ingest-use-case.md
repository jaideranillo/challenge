---
id: TASK-005-13
feature: FEAT-005
title: "RegisterNotificationEventUseCaseImpl: the transactional core, with no network call inside it"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-03, TASK-005-04, TASK-005-05, TASK-005-06, TASK-005-08]
date: 2026-09-20
---

# TASK-005-13: the ingest use case

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/RegisterNotificationEventUseCaseImpl.java` (new)
  - `src/test/java/com/cobre/challenge/application/usecase/RegisterNotificationEventUseCaseImplTest.java` (new)
- Concern: ADR-002 §1.1 steps 2 and 3, in one transaction.

Implements the `port/in` interface FEAT-003 already committed — do not change it or its command/result records.

### The flow, in order

1. `subscriptions = subscriptionRepository.findActiveForEvent(command.clientId(), command.eventType())`.
2. `inserted = eventRepository.insertIfAbsent(new NotificationEvent(...))`, built from the command.
3. `eventCreatedAt` = `command.occurredAt()` when `inserted` is `true`; otherwise `eventRepository.findById(eventId)`'s stored `createdAt`. See criterion 4 below.
4. `traceContext = traceContextPort.currentTraceparent()`.
5. For each subscription: build a `Delivery` (`status = PENDING`, `origin = INGEST`, `attemptCount = 0`, `replayedFrom`/`nextAttemptAt`/`lastError`/`deliveredAt` empty, `clientId` from the **event**, `eventCreatedAt` and `traceContext` from steps 3 and 4), call `pipelineRepository.insertIfAbsent(delivery)`, and on `Optional.empty()` call `findLiveByEventAndSubscription(eventId, subscriptionId)` to recover the existing id.
6. Return `RegisterNotificationEventResult(deliveryIds, newlyCreated)` where `newlyCreated` is true when **at least one delivery row was inserted by this call**.

### Non-negotiable properties

1. **`@Transactional` on this class, and no network call inside it.** No `NotificationQueuePort`, no `SqsClient`, no HTTP client is injected here — the publish is TASK-005-14's, after the commit. ADR-002 §1.1's closing paragraph makes this the same discipline as ADR-001 §1's "nothing is ever enqueued before it is committed".
2. **No `clientId` comparison anywhere in this class.** Tenant isolation is structural: `client_id` is in the query predicate (ADR-003 §2), so a foreign subscription is never returned and there is nothing to compare. Writing a defensive equality check would imply the query might leak, which is the fetch-then-compare pattern §2 corrected away from. A reviewer should see the absence.
3. **Zero subscriptions is a success**, not an exception and not a log at error: the event row is still written, `deliveryIds` is empty, `newlyCreated` reflects the event insert being the only write. ADR-003 §2 and FEAT-005 decision 3.
4. **`eventCreatedAt` comes from the stored event on a re-ingest.** `notification_events` is append-only; if the second command carries a different `occurredAt`, the stored value wins, or new delivery rows would disagree with their own parent event. The `findById` is issued **only** when `insertIfAbsent` returned `false` — do not read unconditionally.
5. **No framework type but `@Transactional`.** No `Tracer`, no `JdbcTemplate`, no Spring `ApplicationEventPublisher`, no `Logger` carrying payload. Constructor injection of the four ports, all `final`.
6. **`content` never reaches a log, a span attribute or an exception message** (ADR-002 §3.1). Log by `event_id` and `delivery_id` only.
7. **No `synchronized`, no `ThreadLocal`.** This runs on a virtual thread across several blocking JDBC calls; either would pin or leak.

Tests here are plain JUnit with hand-written fakes for the four ports (CLAUDE.md: domain and use-case logic, no Spring context): the fan-out over N subscriptions, the zero-subscription case, the replay case returning existing ids with `newlyCreated = false`, and the stored-`createdAt` rule of criterion 4. The database-backed proof is TASK-005-17.

## Out of Scope

- The publish, the after-commit hook, the executor and the failure counter. TASK-005-14.
- The controller, request DTO and HTTP status. TASK-005-15.
- Security. TASK-005-16.
- Any change to `RegisterNotificationEventUseCase`, its command/result records, `Delivery`, `NotificationEvent`, or any port interface. If one seems necessary, stop and raise it — it is an ADR-level change, not this task's.
- Replay (`POST /replay`), the relay, the worker.

## Acceptance Criteria

- [ ] Implements the committed `port/in` interface; that interface and its DTOs are unmodified.
- [ ] `@Transactional` on the class or method, and it is the only framework annotation in the file.
- [ ] No queue port, SQS type, or HTTP client is injected or referenced.
- [ ] No `clientId` equality comparison appears anywhere in the class.
- [ ] Zero matching subscriptions produces a written event, an empty `deliveryIds`, and no exception.
- [ ] `findById` on the event repository is called only when `insertIfAbsent` returned `false`, and its `createdAt` is what reaches `Delivery.eventCreatedAt`.
- [ ] `Optional.empty()` from `insertIfAbsent` leads to `findLiveByEventAndSubscription`, and the recovered id is returned.
- [ ] `newlyCreated` is true when at least one delivery row was inserted by this call.
- [ ] No `synchronized`, no `ThreadLocal`, no reactive type.
- [ ] `content` appears in no log, message, or span attribute.
- [ ] Tests written and passing: fan-out, zero-subscription, replay, and the stored-`createdAt` rule, as plain JUnit with fakes and no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** criterion "no `clientId` comparison" is the control, and it is a structural one. **A09:** no payload logging. **A10:** the zero-subscription and replay paths return normally rather than throwing.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
