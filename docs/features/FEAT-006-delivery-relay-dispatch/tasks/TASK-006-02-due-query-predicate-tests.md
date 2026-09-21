---
id: TASK-006-02
feature: FEAT-006
title: Due-query predicate tests — grace window, circuit, throttle, clock, deliverability, cap
status: Ready for Review
agent: dba
depends_on: [TASK-006-01]
date: 2026-09-21
---

# TASK-006-02: Due-query predicate tests

## Feature

FEAT-006

## Assigned Agent

`dba` — you wrote the statement in TASK-006-01; you pin its behaviour here. The relay use case's
own tests are TASK-006-10 and TASK-006-11 and are not yours.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/ClaimDuePredicateTest.java`
    (extend the merged class)
  - at most one new test class if the existing one grows past readability — if you need a second
    file, name it for the cap specifically (e.g. `ClaimDueInFlightCapTest.java`)
- Concern: every predicate of one SQL statement is asserted, in one place.

Testcontainers against real PostgreSQL, as the merged tests already do. **No mocks, no H2, no
embedded substitute.**

## Scope — the scenarios

Five of these are the Tech Lead's numbered requirements 1-5; 6 and 7 cover TASK-006-01's added
exclusions, and 8 is the starvation regression that pins the per-subscription `LATERAL` shape. Assert on the rows `claimDue` returns **and** on the resulting table state (status
moved to `QUEUED`, `next_attempt_at` pushed to `asOf + 5 minutes`) — a row that is returned but
not transitioned, or transitioned but not returned, is a real bug the return value alone hides.

1. **Grace window, inside.** A `PENDING` row with `created_at = asOf - 10s` and a due
   `next_attempt_at` is **not** claimed.
2. **Grace window, outside.** The same row with `created_at = asOf - 31s` **is** claimed.
3. **Open circuit, still cooling.** Subscription `circuit_state = 'OPEN'`,
   `circuit_opened_at = asOf - 10s`, `circuit_backoff = 1 minute` — the row is **not** claimed.
4. **Active throttle.** Subscription `throttled_until = asOf + 1 minute` — the row is **not**
   claimed. Add the mirror: `throttled_until = asOf - 1 second` **is** claimed, so the test
   distinguishes "throttle respected" from "row never claimable".
5. **Future `next_attempt_at`.** `next_attempt_at = asOf + 1 second` — **not** claimed;
   `asOf` exactly — **is** claimed (the predicate is `<=`).
6. **Deliverability gate.** Three rows on three subscriptions, each otherwise claimable:
   `active = false` — not claimed; `verification_state = 'PENDING_VERIFICATION'` — not claimed;
   `active = true` and `VERIFIED` — claimed. One assertion per exclusion, not one combined row,
   so a regression names which half broke.
7. **Effective in-flight cap.**
   - `CLOSED` circuit, `max_concurrency = 2`, four due rows on one subscription, nothing already
     in flight: exactly 2 claimed.
   - Same, but with 1 row already `PROCESSING` and fresh (`updated_at = asOf`): exactly 1 claimed.
   - Same, but the in-flight row is stale (`updated_at = asOf - 90s`): it is itself a candidate,
     so it does **not** consume cap — assert the claimed count reflects that and includes the
     reclaimed row.
   - `HALF_OPEN` circuit, `max_concurrency = 10`, three due rows: exactly **1** claimed. This is
     ADR-006 §1.2's one-probe rule at the SQL level.
   - `OPEN` circuit whose cooldown has elapsed (`circuit_opened_at = asOf - 2 minutes`,
     `circuit_backoff = 1 minute`), three due rows: exactly **1** claimed. The promotion write
     itself is TASK-006-06's; here you assert only that the cap already behaves as `1` before any
     promotion has happened.
   - The cap is **per subscription**: two subscriptions each at `max_concurrency = 1` with two due
     rows apiece yield 2 claimed rows, one from each — not 1 in total.
8. **Starvation regression — a saturated subscription must not crowd out a quiet one.** This is a
   distinct scenario from 7, not a variation of it: 7 asserts the cap admits the right *count*,
   8 asserts the cap does not consume the *batch* on behalf of rows it will not admit.
   - Subscription A: `CLOSED` circuit, `max_concurrency = 10`, **10,000 due `PENDING` rows**, all
     past the grace window, all with `next_attempt_at <= asOf`. Insert them in one batch
     (`JdbcTemplate.batchUpdate` or a generate-series insert) — do not loop 10,000 single inserts.
   - Subscription B: also deliverable and `CLOSED`, with **exactly 1 due row**, whose
     `next_attempt_at` is *newer* than every one of A's rows, so a naive global ranking would place
     it last.
   - Call `claimDue(batchLimit, asOf)` **once**, with `batchLimit` well below A's backlog
     (use the feature's default of 500).
   - Assert: the returned rows **contain B's delivery id**, and A contributes **at most 10** rows.
     Assert B's row by id, not by count — "501 rows came back" would pass a broken query.
   - Assert B's row is transitioned (`QUEUED`, `next_attempt_at = asOf + 5 minutes`) like any other
     claimed row.
   - Name the test for what it protects, e.g. `saturatedSubscriptionDoesNotStarveQuietSubscription`.
   - This test fails on the pre-LATERAL shape (global candidate pool + global `LIMIT`, cap applied
     as a post-filter) and passes on the per-subscription `LATERAL` shape TASK-006-01 specifies.
     If it passes on both, the fixture is wrong — most likely A's backlog is smaller than
     `batchLimit`, or B's row sorts early enough to survive a global ranking.

Drive every scenario with an explicit `asOf` passed into `claimDue`. **No `Thread.sleep`, no
wall-clock dependence, no `Instant.now()` in an assertion.**

Run each `claimDue` inside a transaction (`TransactionTemplate` or the merged tests' existing
pattern) — the method throws without one, by design.

## Out of Scope

- Changing `DeliveryPipelineJdbcRepository` or the port. If a test reveals the statement is wrong,
  fix it under TASK-006-01's scope and say so; do not widen this task into a redesign.
- `ClaimDueConcurrencyTest` — `SKIP LOCKED` behaviour is already covered and unchanged.
- SQS, LocalStack, the relay use case, the scheduler, and circuit promotion.
- Any migration or index.

## Acceptance Criteria

- [ ] All eight scenario groups above are implemented as separate, independently named tests.
- [ ] Scenario 8 (starvation regression) exists as its own test: a subscription with 10,000 due
      `PENDING` rows and `max_concurrency = 10`, plus a second subscription with exactly 1 due row;
      a single `claimDue` cycle returns the second subscription's delivery id, and the saturated
      subscription contributes at most 10 rows.
- [ ] Each test asserts both the returned rows and the post-claim table state.
- [ ] Fixtures build subscriptions that are `active` and `VERIFIED` by default, with each test
      opting into exactly the one deviation it is about.
- [ ] No `Thread.sleep`, no mocks, no H2 — real PostgreSQL via Testcontainers.
- [ ] Every `claimDue` call runs inside an explicit transaction.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
