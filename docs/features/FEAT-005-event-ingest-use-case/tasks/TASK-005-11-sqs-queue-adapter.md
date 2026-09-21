---
id: TASK-005-11
feature: FEAT-005
title: "SqsNotificationQueueAdapter: the first implementation of NotificationQueuePort"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-02]
date: 2026-09-20
---

# TASK-005-11: `NotificationQueuePort` over SQS

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapter.java` (new)
- Concern: turning a `DeliveryPointer` into a `SendMessage`.

The port has existed since FEAT-003 with no implementation; ingest is its first caller.

**The envelope is ADR-004 SS1's four fields and nothing more**: `deliveryId`, `subscriptionId`, `attemptHint`, `traceparent`. Serialize as JSON with Jackson (already on the classpath via `spring-boot-starter-webmvc`). No event content, no target URL, no secret, no client id — the flatness is a stated design property of `DeliveryPointer` and the message body must not exceed it.

**`traceparent` goes in the SQS message attribute as well as the body** (ADR-002 §3.1): the consumer reads the attribute first and falls back to `deliveries.trace_context`. Use the attribute name `traceparent`. When the pointer's `traceparent` is `empty()`, set no attribute at all rather than an empty-string one — an absent attribute is what triggers the consumer's documented fallback; a blank one would be parsed and fail.

**Resolve the queue URL once, in the constructor**, via `GetQueueUrl` from the configured queue name. Not per publish: this path exists to shave latency off ingest, and a second round trip per message would defeat it. A missing queue therefore fails at startup, which is the right time to find out.

**`publishBatch`** is on the interface and must be implemented, but its caller (the relay) is out of scope for this feature. Implement it as a real `SendMessageBatch` with the SDK's 10-message chunking, or delegate to `publish` in a loop with a comment naming the relay as the future caller and the batching as its concern — either is acceptable; what is not acceptable is throwing `UnsupportedOperationException` (LSP) or building a batching/backpressure layer nothing calls (YAGNI).

**No exception handling here.** Let SDK exceptions propagate. The caller's catch-all is TASK-005-14's job, and swallowing here would hide a failure from the counter that exists to see it.

**No `synchronized`, anywhere in this class.** The sync `SqsClient`'s blocking HTTP unmounts a virtual thread correctly; a `synchronized` block around it pins a carrier thread for a full network round trip, which turns a best-effort publish into a throughput cliff under fan-out. `SqsClient` is thread-safe and needs no guarding.

## Out of Scope

- Retry, backoff, circuit-breaking or a dead-letter path around the publish. Best-effort means best-effort (ADR-002 §1.1 step 5); the relay is the guaranteed path (ADR-001 §1, §2).
- Catching or logging publish failures. TASK-005-14.
- Consuming from SQS, DLQ handling, `ChangeMessageVisibility`.
- The `SqsClient` bean and property binding. TASK-005-02.
- Tests. TASK-005-12.

## Acceptance Criteria

- [ ] Implements `NotificationQueuePort`; both methods implemented, neither throws `UnsupportedOperationException`.
- [ ] The message body carries exactly the four `DeliveryPointer` fields; no fifth field, no payload, no secret, no URL.
- [ ] `traceparent` is set as a message attribute when present and omitted entirely when absent.
- [ ] The queue URL is resolved once at construction from the configured queue name.
- [ ] No `synchronized` block; no `SqsAsyncClient`; no reactive type.
- [ ] SDK exceptions propagate; no catch block swallows a publish failure.
- [ ] No log line includes event content — this class never sees it, and must not gain a parameter that does.
- [ ] Tests written and passing: owned by TASK-005-12, which must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A09:** the envelope is metadata only, by construction. **A04:** no secret material crosses this boundary — the pointer carries `secret_ref`'s subject not at all, and must not start to.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
