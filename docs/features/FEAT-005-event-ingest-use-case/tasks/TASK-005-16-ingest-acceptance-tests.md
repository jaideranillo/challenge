---
id: TASK-005-16
feature: FEAT-005
title: "Acceptance: replaying an event_id creates no duplicate rows, and a client with no subscription still gets 202"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-15]
date: 2026-09-20
---

# TASK-005-16: ingest acceptance tests, 1 of 2

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/in/web/ingest/EventIngestAcceptanceTest.java` (new)
- Concern: two of the three acceptance cases the Tech Lead named, end to end.

`@SpringBootTest` importing `TestcontainersConfiguration` — real Postgres, real LocalStack SQS, real HTTP through the controller. No mocked SQS (CLAUDE.md), no H2, no stubbed port.

The endpoint is **unauthenticated**: producer-to-gateway authentication is out of scope for FEAT-005 by Tech Lead directive of 2026-09-20, so this test sets up no credential and needs none. If Spring Boot's default security still challenges the request, the minimum needed to let the test reach the controller is a test-scoped configuration in this file — **not** a production `SecurityFilterChain`, which belongs to the deferred follow-up.

### Case A — replaying the same `event_id` creates no duplicate rows

Seed one client with two active, verified subscriptions matching the event type. `POST` the event, then `POST` the byte-identical body again.

Assert:

1. Both responses are `202`.
2. `notification_events` holds exactly **one** row for the `event_id`, and its columns are the first request's values.
3. `deliveries` holds exactly **two** rows for the `event_id`, one per subscription.
4. The second response's `deliveryIds` equal the first's, as a set.
5. The second response's `newlyCreated` is `false`; the first's is `true`.
6. Neither delivery row's `attempt_count`, `status` or `event_created_at` changed between the two requests.

Also send a third request with the same `event_id` but a **different** `occurredAt` and `content`, and assert the stored event and both `event_created_at` values are still the first request's — the re-ingest rule of FEAT-005 decision 5.

### Case B — a client with no matching subscription

Two sub-cases, both `202`:

1. A client with an **active subscription for a different `event_type`**.
2. A client with **no subscription at all**.

In both: `notification_events` holds the row, `deliveries` holds **zero** rows for that `event_id`, and the response's `deliveryIds` is empty.

### Case C — the fan-out does not cross tenants

Seed client `X` and client `Y`, each with an active subscription for the **same** `event_type`. Ingest an event for `X`. Assert exactly one delivery row exists, its `subscription_id` is `X`'s and its `client_id` is `X`. This is the A01 control from the feature's security table, and it is cheap to assert here where both tenants exist.

## Out of Scope

- The forced-publish-failure case. TASK-005-17.
- Asserting what was published to SQS on the success path. TASK-005-12 owns envelope assertions; a timing-dependent queue assertion here would make the suite flaky for no added coverage.
- The relay, the worker, delivery attempts, replay via `POST /replay`.
- Editing production code. A failure here is the owning task's to fix.

## Acceptance Criteria

- [ ] `@SpringBootTest` with real Postgres and real LocalStack via `TestcontainersConfiguration`; no mocked SQS, no H2.
- [ ] Requests go through the real HTTP endpoint, not by calling the use case directly.
- [ ] No production `SecurityFilterChain`, `@PreAuthorize` or permit-all matcher is added by this task; any security setup needed to reach the controller is test-scoped.
- [ ] Case A asserts all six points plus the differing-`occurredAt` re-ingest rule.
- [ ] Case B covers both sub-cases and asserts zero `deliveries` rows in each.
- [ ] Case C asserts exactly one delivery row and its owning tenant.
- [ ] Row counts are read back from the database, not inferred from response bodies.
- [ ] No test asserts on a log line; no test prints `content`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** case C is the cross-tenant fan-out control. **A10:** cases A and B prove the two idempotent/empty outcomes return `202` rather than an error.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
