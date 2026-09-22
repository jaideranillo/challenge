---
id: TASK-008-29
feature: FEAT-008
title: Cursor pagination exactly-once under concurrent inserts (named test 6)
status: Not Started
agent: backend-engineer
depends_on: [TASK-008-02, TASK-008-11, TASK-008-25]
date: 2026-09-21
---

# TASK-008-29: Cursor Pagination Exactly-Once Test

## DEFERRED IN THIS PHASE — DO NOT IMPLEMENT YET

**This entire task is Testcontainers work and is deferred on Tech Lead direction of 2026-09-21,
the same phase rule FEAT-007 ran under.** The property under test is what PostgreSQL does with a
keyset predicate while rows are being inserted; no unit test can produce it.

**What to do now: nothing.** The merged `DeliveryPageCursorTest` already covers the cursor codec
as a unit test and stays green; that is the unit-testable part and it is done. Leave this task's
`status` at `Not Started` until the Tech Lead lifts the deferral.

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope (when implemented)

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/CursorPaginationIntegrationTest.java` (new)
- Concern: one property, proven end to end.

## Named test 6 — the user's wording, verbatim

> **Cursor pagination returns each row exactly once across pages with concurrent inserts.**

## Why keyset and not offset (ADR-005 §1, context for whoever implements this)

Offset pagination degrades on a write-hot table **and** it is the thing this test would catch: with
`OFFSET`, a row inserted before the cursor's position shifts every later page by one, so a client
paging through sees a row twice or misses one entirely. Keyset pagination on
`(event_created_at, delivery_id)` is immune to that because the position is a value, not a count.
This test is the proof of that claim, which is why it must include concurrent inserts rather than
paging a static table.

## The scenario

1. Seed N rows for one tenant with varied `event_created_at` values, including **ties** — several
   rows sharing the same `event_created_at`, which is exactly where a single-column keyset breaks
   and where the `delivery_id` tiebreaker earns its place.
2. Page through with a small `limit`, following `nextCursor`, as the authenticated tenant over
   HTTP.
3. **While paging**, insert additional rows concurrently from another thread — some with
   timestamps **before** the current cursor position, some **after**, some inside the window
   already read.
4. Collect every delivery id returned across all pages.

Assertions:

- **No id appears twice** across all pages. This is the headline.
- Every row that existed **before paging began** appears exactly once.
- Rows inserted concurrently **may or may not** appear, depending on where they landed relative to
  the cursor — assert only that if one appears, it appears once. Do not assert that they all
  appear, and do not assert that none does: a keyset snapshot makes neither guarantee, and a test
  asserting either is wrong and will flake.
- Tied `event_created_at` values are ordered consistently by `delivery_id` and are not skipped or
  repeated.
- The last page returns no `nextCursor`.
- Every row returned belongs to the querying tenant, with `CLIENT_B` rows present in the table
  throughout.

Run it through the real endpoint with a real token, on the API pool with RLS active — the same
preconditions TASK-008-28 asserts.

## Out of Scope

- The cursor codec's own encode/decode tests. `DeliveryPageCursorTest` is merged and covers them;
  do not duplicate.
- Clamping and default-window behavior — TASK-008-17's unit tests own those.
- Cross-tenant and replay scenarios — TASK-008-28.
- Any production-code change. If the test reveals a defect, report it; the fix belongs in
  TASK-008-15.
- Performance measurement.

## Acceptance Criteria

**Every criterion below is `DEFERRED — Testcontainers`. None is to be satisfied in this phase.**

- [ ] **Named test 6** exists as its own named method with concurrent inserts running during
      paging.
- [ ] No delivery id is returned twice across all pages — asserted over the full collected set.
- [ ] Every pre-existing row appears exactly once.
- [ ] Concurrently inserted rows are asserted only for at-most-once, never for presence.
- [ ] The seed data contains `event_created_at` ties, and the test asserts they are neither
      skipped nor repeated.
- [ ] The last page carries no `nextCursor`.
- [ ] Another tenant's rows are present in the table and appear in no page.
- [ ] The test runs over the real endpoint, with a real token, on the API pool with RLS active.
- [ ] The test is deterministic — no `Thread.sleep` as synchronization, no assertion that depends
      on insert timing.
- [ ] No new OWASP Top 10:2025 exposure introduced.

## Definition of Done

**In this phase: nothing to do.** Leave `status` at `Not Started`.

When the Tech Lead lifts the deferral: test written and passing locally against Testcontainers,
run repeatedly to confirm it is not flaky. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
