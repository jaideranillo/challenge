---
id: TASK-004-15
feature: FEAT-004
title: "Testcontainers: findPage paging across boundaries, event_created_at filtering for replays, tenant scoping and index plan"
status: Not Started
agent: dba
depends_on: [TASK-004-14]
date: 2026-09-20
---

# TASK-004-15: `findPage` tests

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryFindPageTest.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/cursor/DeliveryPageCursorTest.java` (new)
- Concern: paging correctness and the tenant/date-column properties.

### `DeliveryPageCursorTest` — plain JUnit, no Spring

1. Round trip: encode then decode returns the same `Instant` and `UUID`, with the `Instant` at full persisted precision (microseconds in Postgres; a codec that truncates to seconds breaks paging inside a fan-out).
2. Malformed input throws: empty string, non-base64, valid base64 of garbage, right arity with an unparseable timestamp, wrong arity. **Each must throw, and none may return a default cursor.**
3. Encoding is stable: the same pair always produces the same string.

### `DeliveryFindPageTest` — real Postgres via `TestcontainersConfiguration`

**The `event_created_at` property, which is why this feature exists:**

4. Seed a delivery whose `event_created_at` is 30 days ago and whose `created_at` is today — the replay shape. A query filtered to a window containing the event's date but **not** today **returns it**. A query filtered to today but not the event's date **does not**. Without this test the whole denormalization is unverified, and it is the one assertion that would have caught the first pass's choice of `created_at`.
5. Ordering is by `event_created_at`, not `created_at`: seed three rows whose two orderings disagree and assert the returned order follows the event timestamps.

**Paging:**

6. Full traversal: 25 rows, `limit = 10`, page through to exhaustion. Assert every id is seen exactly once, nothing is skipped or repeated, and `hasMore` is true on pages 1-2 and false on page 3.
7. **The fan-out boundary, the test a naive keyset fails:** seed 10 rows with an **identical** `event_created_at` (one event fanned out to 10 subscriptions), page with `limit = 3`, and assert all 10 come back exactly once across four pages. Without the `delivery_id` tiebreak this loses or repeats rows.
8. `limit` exactly equal to the row count returns `hasMore = false`, not an empty extra page.
9. An empty result returns an empty list and `hasMore = false`, never null.

**Filters:**

10. `eventCreatedFrom` only, `eventCreatedTo` only, both, neither. Assert boundary inclusivity matches the implementation (`>=` from, `<` to) and that the javadoc agrees with the test.
11. `status` filter returns only that status; combined with a date window, both apply.
12. Every filter combination still scopes to the tenant. Parameterize over the combinations with a foreign-tenant row seeded throughout, and assert it never appears in any of them. **This is the A01 control for this method** and the one place a filter-composition bug could drop the `client_id` predicate.

**Tenant and cursor:**

13. A cursor obtained as tenant A, replayed by tenant B, returns only tenant B's rows. The cursor carries no tenant and confers no access.
14. A malformed cursor propagates the codec's exception out of `findPage`; it does **not** return page 1.

**Plan:**

15. `EXPLAIN (FORMAT JSON)` shows `idx_deliveries_client_event_created_at` used and no sequential scan on `deliveries`, for the no-filter case and for the date-window case. Seed enough rows that a seq scan is not the planner's cheapest option. Follow the existing style in `src/test/java/com/cobre/challenge/schema/`.
16. The plan does **not** reference `idx_deliveries_client_created_at`, which TASK-004-01 dropped. A hit there would mean the query is still on the old column.

## Out of Scope

- `limit` clamping and the default date window (ADR-005 §1; applied on the way in, not here).
- The public `delivery_status` mapping.
- `findById` (TASK-004-13's tests).
- Any pipeline-port method.
- HTTP-level behavior, 404-vs-403, response DTOs.
- No LocalStack, no SQS.

## Acceptance Criteria

- [ ] All sixteen items above exist as tests and pass.
- [ ] Test 4 uses a fixture where `event_created_at` and `created_at` deliberately differ by ~30 days and asserts both directions (in-window included, today's-window excluded).
- [ ] Test 7 seeds 10 rows sharing one identical `event_created_at` and proves all 10 are returned exactly once across pages.
- [ ] Test 12 is parameterized over filter combinations with a foreign-tenant row present throughout.
- [ ] Test 14 asserts the exception propagates rather than degrading to page 1.
- [ ] Tests 15 and 16 assert the new index is used and the dropped one is not referenced.
- [ ] `DeliveryPageCursorTest` asserts microsecond-precision round trip and that every malformed form throws.
- [ ] No H2, no mocked JDBC or `ResultSet` in `DeliveryFindPageTest`.
- [ ] Tests written and passing: `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeliveryFindPageTest"` and `--tests "...cursor.DeliveryPageCursorTest"` green with Docker running, full suite still green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** tests 12 and 13. **A05:** the cursor tests prove decode rejects rather than binds garbage. **A10:** test 14. No real payload or secret in any fixture (**A09**).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
