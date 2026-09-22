---
id: TASK-008-28
feature: FEAT-008
title: Cross-tenant 404, replay 409 and double-replay integration tests (named tests 1, 4, 5)
status: Not Started
agent: backend-engineer
depends_on: [TASK-008-02, TASK-008-11, TASK-008-16A, TASK-008-25, TASK-008-26, TASK-008-27]
date: 2026-09-21
---

# TASK-008-28: Tenant Isolation and Replay Integration Tests

## DEFERRED IN THIS PHASE — DO NOT IMPLEMENT YET

**This entire task is Testcontainers work and is deferred on Tech Lead direction of 2026-09-21,
the same phase rule FEAT-007 ran under.** Every scenario below needs a real PostgreSQL, a real
HTTP request and a real signed token; none is expressible as a unit test.

**What to do now: nothing.** The specification below is the durable record so nothing is lost
between phases. The unit-testable halves of these scenarios are already owned elsewhere and are
written now — TASK-008-18 (foreign id yields `Optional.empty()`), TASK-008-19 (every non-`DEAD`
status is rejected), TASK-008-26 (the 409 and 404 status mapping against a mocked use case). Those
are **halves**, not substitutes: a mock cannot prove RLS, a partial unique index, or a token's
`client_id` reaching a SQL predicate.

Leave this task's `status` at `Not Started` until the Tech Lead lifts the deferral.

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope (when implemented)

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/SelfServiceApiIsolationTest.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/ReplayEndpointIntegrationTest.java` (new)
  - a test-fixture helper for minting tokens with the TASK-008-02 key (new)
- Concern: the end-to-end properties the Tech Lead named. Testcontainers PostgreSQL, the real
  filter chain, real RS256 tokens signed with the committed dev key.

## Named tests owned here — the user's wording, verbatim

> **1. A token for client A requesting client B's delivery gets 404.**
>
> **4. Replaying a non-DEAD delivery returns 409.** (HTTP half; the use-case half is TASK-008-19)
>
> **5. Two rapid replays create one delivery.**

## The scenarios

Run the **full stack**: real filter chain, real decoder against the dev public key, the API pool
as `challenge_api`, RLS active. A test that bypasses the chain or connects as the table owner
proves nothing (ADR-007 §5.4) — assert the preconditions first, as TASK-008-11 does.

| # | Scenario | Expected |
|---|---|---|
| 1 | **Named test 1.** Seed a delivery for `CLIENT_B`. `GET /notification_events/{id}` with a valid token whose `client_id` is `CLIENT_A` and which holds `notifications:read` | **404**, not 403, not 200. Body identical to scenario 2's |
| 2 | Same request for an id that exists nowhere | **404**, byte-identical body to scenario 1 |
| 3 | `GET /notification_events` as `CLIENT_A` with `CLIENT_B` rows present | only `CLIENT_A`'s rows; `CLIENT_B`'s appear nowhere, in any page |
| 4 | **Named test 4.** Seed a delivery for `CLIENT_A` in a non-`DEAD` status. `POST .../replay` with a valid replay-scoped token and an `Idempotency-Key` | **409** |
| 5 | Replay a `DEAD` delivery owned by the caller | **202**, a new row exists with `origin = 'REPLAY'`, `replayed_from` = the original id, `status = 'PENDING'`, `attempt_count = 0`, and `event_created_at` copied from the original |
| 6 | The original `DEAD` row after scenario 5 | **unchanged in every column** — assert it directly against the database |
| 7 | **Named test 5.** Two replays of the same `DEAD` delivery issued in rapid succession — concurrently, from two threads, with the **same** `Idempotency-Key`, and again in a second run with **different** keys | **exactly one** new `deliveries` row for the pair. One response is 202, the other 409. Assert the row count by querying the database, not by inspecting responses. The different-key run is the important one: it proves `idx_deliveries_live_pair` — not the guard — is the guarantee |
| 8 | Replay another tenant's `DEAD` delivery | **404**, never 409. 409 must be unreachable for a foreign id (ADR-007 §5.5) |
| 9 | A token with only `notifications:read` calling replay | **403** |
| 10 | A token with only `notifications:replay` calling the list endpoint | **403** — no hierarchy (ADR-007 §4) |
| 11 | No token; an expired token; a wrong-audience token; a token with no `client_id` | **401** each, with **byte-identical** bodies |
| 12 | Any unmapped path, e.g. `GET /nope` | **403** from the terminal deny chain |
| 13 | Replay a `DEAD` delivery whose `(event_id, subscription_id)` pair **also has a `DELIVERED` row** | **409**, and **no** new row is inserted — assert the row count against the database. This is the other half of ADR-005 §1's 409 and it is guarded inside `insertReplayIfAbsent`'s statement (TASK-008-16A), not by `idx_deliveries_live_pair`, whose predicate excludes `DELIVERED` on purpose |
| 14 | Replay the **same** `DEAD` delivery twice sequentially, marking the first replay `DELIVERED` between the two calls | **409** on the second, **no** second replay row. This is the two-transaction window the guard closes; without it the live-pair index no longer applies and a second row would be inserted |

Scenarios 11's tokens come from TASK-008-02's script flags; that is what those flags exist for.

Scenario 7 stays the `idx_deliveries_live_pair` proof and scenarios 13-14 are the `NOT EXISTS`
proof. They are different mechanisms guarding different states; neither substitutes for the other.

## Out of Scope

- Cursor pagination under concurrent inserts — TASK-008-29.
- The RLS behavioral matrix — TASK-008-11.
- Any production-code change. If a scenario fails when implemented, report it; the fix belongs in
  the owning task.
- Performance or load assertions.

## Acceptance Criteria

**Every criterion below is `DEFERRED — Testcontainers`. None is to be satisfied in this phase.**

- [ ] The suite runs against Testcontainers PostgreSQL with the real filter chain and real signed
      tokens; no security bypass, no mocked authentication, no owner-role connection.
- [ ] A precondition assertion confirms the API path is subject to RLS before any scenario runs.
- [ ] **Named test 1** exists as its own named method and asserts 404, explicitly not 403.
- [ ] Scenario 1's and scenario 2's response bodies are asserted **equal**.
- [ ] **Named test 4** exists as its own named method and asserts 409 over HTTP.
- [ ] **Named test 5** exists as its own named method, issues the two replays concurrently, and
      asserts **exactly one** new row by querying the database — in both the same-key and
      different-key variants.
- [ ] Scenario 6 asserts the original `DEAD` row is unchanged column by column.
- [ ] Scenario 8 asserts 404, and the test comment states why 409 would be a leak.
- [ ] Scenarios 9 and 10 prove the two scopes do not imply each other.
- [ ] Scenario 11 asserts four distinct failure causes produce byte-identical 401 bodies.
- [ ] Scenario 12 asserts the terminal deny chain.
- [ ] Scenarios 13 and 14 assert 409 **and** a database row count, not just a status code — a
      wrongly-inserted second row with a 409 response would pass a status-only assertion.
- [ ] No test weakens or deletes an assertion to make it pass.
- [ ] No new OWASP Top 10:2025 exposure introduced.

## Definition of Done

**In this phase: nothing to do.** Leave `status` at `Not Started`.

When the Tech Lead lifts the deferral: tests written and passing locally against Testcontainers.
**Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
