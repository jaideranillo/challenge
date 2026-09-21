---
id: TASK-005-17
feature: FEAT-005
title: "Acceptance: a forced SQS failure after commit leaves the rows durable and the response 202"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-14, TASK-005-16]
date: 2026-09-20
---

# TASK-005-17: ingest acceptance tests, 2 of 2

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/in/web/ingest/IngestPublishFailureAcceptanceTest.java` (new)
- Concern: proving ADR-002 §1.1's "if step 5 fails, the `deliveries` rows are already durable".

`@SpringBootTest` importing `TestcontainersConfiguration`. **Real LocalStack SQS, no mock** — CLAUDE.md's testing policy names mocked SQS specifically, so the failure is injected into the real client's target, not by stubbing the port.

### How to force the failure

Point the adapter at a queue that does not exist, and let real SQS reject the `SendMessage`. Two ways, in order of preference:

1. **Delete the `deliveries` queue** from LocalStack after the context has started and the adapter has resolved its URL (TASK-005-11 resolves once, at construction). The held URL then refers to a queue that is gone and `SendMessage` fails with a real SDK exception against a real service. Restore or recreate it in teardown so other tests in the suite are unaffected.
2. If teardown isolation proves fragile, override `challenge.sqs.queues.deliveries` for this test class to a queue name created and then deleted during setup, so only this class's context is affected.

Do **not** stub `NotificationQueuePort`, do not mock `SqsClient`, and do not add a production-code failure-injection flag.

### Assertions

1. The response is **`202`**, with the normal body — `deliveryIds` populated, `newlyCreated` true. The producer sees no difference at all.
2. `notification_events` holds the row and `deliveries` holds one `PENDING` row per matched subscription, with every column correct. **The rows are durable despite the publish failing.**
3. The `notification.ingest.publish.failed` counter has incremented by the number of failed publishes. Because the publish is off the request thread, poll the counter until it reaches the expected value with a bounded timeout rather than reading it once immediately — and fail the test on timeout, do not pass on a zero read.
4. No exception surfaced to the caller, and the request did not take materially longer than a successful ingest (the publish is not on the request thread, so it cannot have blocked it).
5. A follow-up ingest of the **same** `event_id` after the failure is still `202` and still creates no duplicate rows — a lost publish must not corrupt idempotency.

## Out of Scope

- Any retry or recovery of the failed publish. The relay is the guaranteed path and is out of scope for this feature; the delivery simply waits, which is the accepted state of the world at this step. Do not assert eventual delivery.
- Asserting the log message's text. The counter is the contract; a log format is not.
- The success-path envelope. TASK-005-12.
- Editing production code. A failure here is TASK-005-14's or TASK-005-11's to fix.

## Acceptance Criteria

- [ ] The failure is injected against real LocalStack SQS; `NotificationQueuePort` and `SqsClient` are neither mocked nor stubbed, and no production failure-injection flag is added.
- [ ] Assertion 1: `202` with the normal body.
- [ ] Assertion 2: every expected row is present and correct, read back from the database.
- [ ] Assertion 3: the counter is polled to the expected value with a bounded timeout, and a timeout fails the test.
- [ ] Assertion 4: no exception reaches the caller.
- [ ] Assertion 5: the replay after the failure is `202` with no duplicate rows.
- [ ] The queue state is restored in teardown so the rest of the suite is unaffected — verified by running the full `./gradlew test`, not by inspection.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative category and this test is its control: the request fails open with respect to the optional step and the data stays durable. **A09:** assertion 3 proves the silent failure is still counted.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
