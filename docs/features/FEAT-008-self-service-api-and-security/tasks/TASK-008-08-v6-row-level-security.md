---
id: TASK-008-08
feature: FEAT-008
title: V6 migration — row level security policies on the tenant tables
status: Ready for Review
agent: dba
depends_on: [TASK-008-07]
date: 2026-09-21
---

# TASK-008-08: `V6` — Row Level Security

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/resources/db/migration/V6__row_level_security.sql` (new)
  - `src/test/java/com/cobre/challenge/schema/RowLevelSecurityPolicyTest.java` (**deferred this
    phase** — specified below, not written now)

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
The catalog test below is **DEFERRED — Testcontainers**. Write the migration, verify with
`./gradlew compileJava compileTestJava`, and report the test as specified and pending. Do not
imitate a policy with H2 or a mock: RLS is the thing under test and no fake has it.
- Concern: `ENABLE ROW LEVEL SECURITY` and one policy per tenant table. No roles (TASK-008-07),
  no pools (TASK-008-09), no application code.

## The policies (ADR-007 §5.4)

Enable RLS on all four tenant tables and create one `SELECT` policy each:

| Table | Policy shape |
|---|---|
| `notification_events` | `USING (client_id = current_setting('app.client_id', true))` |
| `deliveries` | same |
| `subscriptions` | same |
| `delivery_attempts` | **has no `client_id` column.** Express it against the parent row: the attempt is visible when its `deliveries` row is. Write it as an `EXISTS` against `deliveries` matching `delivery_id` and the same `current_setting` predicate |

Scope each tenant policy `TO challenge_api` explicitly. Role-agnostic policies are no longer an
option: with `FORCE ROW LEVEL SECURITY` on (below), a policy that does not name its role applies to
the owner too, and the pipeline's exemption must be the one named mechanism, not a side effect of
how a policy was scoped.

**`current_setting('app.client_id', true)` with the second argument `true` is mandatory.** It
returns `NULL` when the variable was never set, and `client_id = NULL` matches no row. That is
the fail-closed property of ADR-007 §5.4 and the whole reason RLS is the second layer: a query
that skipped the session binding returns **zero rows**, never another tenant's rows. Without the
`true`, an unset variable raises an error instead, which is a different and worse failure.

## `FORCE ROW LEVEL SECURITY` **is** set (reversed, Tech Lead review of 2026-09-21)

An earlier version of this task left `FORCE` off, so that the pipeline could escape the policies by
being the table **owner**. That is reversed. Record the reason in a migration comment:

Owner-exemption is an implicit escape that comes bundled with `ALTER`, `DROP` and `TRUNCATE` — the
privilege wanted (read across tenants) was not the privilege granted (own the schema), and nothing
in the catalog said so. `V5` now separates the two: `challenge_owner` owns and never logs in,
`challenge_pipeline` escapes the policies by an **explicit** `BYPASSRLS` and holds no DDL, and
`challenge_api` has neither.

With ownership separated, `FORCE ROW LEVEL SECURITY` costs the pipeline nothing (a `BYPASSRLS` role
bypasses RLS whether or not `FORCE` is set) and closes the remaining hole: the Flyway/migration user
is a member of `challenge_owner`, and without `FORCE` any connection under that membership would
read every tenant's rows with no policy applying. `FORCE` makes ownership stop being an authorization
decision.

This also brings the design back inside ADR-007 §5.4, which requires `FORCE` whenever the owner and
the pipeline role are the same principal — the combination the previous shape had, with `FORCE` off.
**No ADR amendment is needed**: the ADR's sentence is being complied with, not changed.

**Fallback form.** If `BYPASSRLS` cannot be granted on the target instance (TASK-008-07 records the
case), the pipeline's exemption is instead an explicit permissive policy on each of the four tables:
`... FOR ALL TO challenge_pipeline USING (true) WITH CHECK (true)`. Use one form or the other, never
both, and say which in the handover. The catalog test asserts whichever is used.

## The test — DEFERRED, Testcontainers, implement only when the Tech Lead requests it

`RowLevelSecurityPolicyTest`, Testcontainers, catalog-level only:

- `pg_class.relrowsecurity` is true for all four tables.
- `pg_class.relforcerowsecurity` is **true** for all four — the deliberate choice above, asserted so
  a later "the pipeline broke, turn FORCE off" change has to come past this test and its comment.
- A tenant policy exists on each of the four tables, scoped `TO challenge_api`, and each policy's
  expression contains `current_setting('app.client_id', true)`.
- The pipeline's exemption exists in exactly one form: either `challenge_pipeline` has
  `rolbypassrls` and no permissive `TO challenge_pipeline` policy exists, or such a policy exists on
  all four tables and `rolbypassrls` is false. Never both, never neither.

Behavioral proof (zero rows with no tenant set, correct rows with one set) is **TASK-008-11**,
which needs the API pool. Do not duplicate it here.

## Out of Scope

- Any behavioral RLS test through a connection — TASK-008-11.
- Roles and grants — TASK-008-07.
- `INSERT`/`UPDATE`/`DELETE` policies. `challenge_api` holds `SELECT` only (TASK-008-07), so a
  write policy would guard a privilege that does not exist (YAGNI).
- Any partitioning work. `deliveries` is not partitioned in `V2`; ADR-003 §3's monthly
  partitioning has not been implemented, so there is nothing to reconcile.
- Any table, column or index change.

## Acceptance Criteria

- [ ] RLS is enabled on `notification_events`, `deliveries`, `subscriptions` and
      `delivery_attempts`.
- [ ] Every policy uses `current_setting('app.client_id', true)` — with the `true` — so an unset
      variable yields `NULL` and matches nothing.
- [ ] The `delivery_attempts` policy is expressed against its parent `deliveries` row, since the
      table has no `client_id` column.
- [ ] `FORCE ROW LEVEL SECURITY` **is** set on all four tables, and the migration comment states
      why, naming the owner/pipeline separation of `V5` as what makes it safe.
- [ ] Each tenant policy is scoped `TO challenge_api` explicitly.
- [ ] The pipeline's RLS exemption exists in exactly one explicit form, and the handover says which.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01/A10: fail closed by construction).
- [ ] **DEFERRED — Testcontainers:** `RowLevelSecurityPolicyTest` asserts `relrowsecurity` true,
      `relforcerowsecurity` false, and one matching policy per table.
- [ ] **DEFERRED — Testcontainers:** every merged pipeline test still passes unchanged; the
      pipeline connection must be completely unaffected by this migration. If any pipeline test
      breaks when the suite is eventually run, the policy scoping is wrong — fix the scoping,
      never the pipeline test.

## Definition of Done

Migration written and compiling; the deferred tests specified, not written. **Do not run
`./gradlew test` or `./gradlew build`** — verify with `./gradlew compileJava compileTestJava`.
**Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
