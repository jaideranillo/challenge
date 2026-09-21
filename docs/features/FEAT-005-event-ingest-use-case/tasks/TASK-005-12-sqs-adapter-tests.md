---
id: TASK-005-12
feature: FEAT-005
title: "LocalStack test: the published envelope and its traceparent attribute"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-01, TASK-005-11]
date: 2026-09-20
---

# TASK-005-12: `SqsNotificationQueueAdapter` tests

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapterTest.java` (new)
- Concern: proving the envelope against real SQS.

**Real LocalStack SQS, no mock** — CLAUDE.md's testing policy names mocked SQS specifically. Use `TestcontainersConfiguration`'s LocalStack (TASK-005-01) and receive the message back through a real `SqsClient`; assert on what SQS actually holds, not on what the SDK was asked to send.

Cases:

1. **`publish` puts a receivable message on the `deliveries` queue**, and its body parses to exactly the four `DeliveryPointer` fields with the values passed in.
2. **The body has no fifth field.** Assert the parsed JSON's key set equals the four expected names. This is the test that catches someone later "just adding" the client id or the target URL.
3. **`traceparent` present** yields a message attribute named `traceparent` with that value, and it also appears in the body.
4. **`traceparent` absent** yields **no** `traceparent` message attribute — assert its absence, not that it is blank. An empty-string attribute would defeat the consumer's documented fallback to `deliveries.trace_context` (ADR-002 §3.1).
5. **`publishBatch` with more than one pointer** puts every message on the queue, each individually receivable and parseable. If the implementation chunks at 10, include a case above 10.

## Out of Scope

- The use case, the controller, the database. Nothing here touches Postgres.
- Publish-failure behavior. TASK-005-17 covers it end to end, where it is meaningful.
- Consuming, DLQ, visibility timeout, or queue attribute assertions — `SqsQueueConfigurationTest` already owns the attributes.
- Editing the adapter. A failure is TASK-005-11's to fix.

## Acceptance Criteria

- [ ] Real LocalStack SQS via Testcontainers; no mocked `SqsClient`, no stubbed port.
- [ ] All five cases present and passing.
- [ ] Case 2 asserts the exact key set of the body, not merely the presence of the four fields.
- [ ] Case 4 asserts the attribute is absent.
- [ ] Messages are received back from the queue and asserted on; no test asserts only on an SDK request object.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A09:** case 2 is the standing control that no payload or secret reaches the queue.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
