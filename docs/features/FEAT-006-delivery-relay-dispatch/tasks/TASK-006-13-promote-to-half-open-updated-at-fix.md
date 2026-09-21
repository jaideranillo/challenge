---
id: TASK-006-13
feature: FEAT-006
title: Fix promoteToHalfOpen SQL — drop the out-of-contract updated_at write
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-21
---

# TASK-006-13: Fix `promoteToHalfOpen` SQL — drop the out-of-contract `updated_at` write

## Feature

FEAT-006

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate
task, not scope creep on this one.

## Context — the bug

`SubscriptionJdbcRepository.promoteToHalfOpen` (merged in FEAT-004) sets `updated_at = :as_of`
alongside `circuit_state`:

```
 SET circuit_state = 'HALF_OPEN'::circuit_state,
     updated_at = :as_of
```

That extra column write contradicts two contracts that are already in the tree:

- `SubscriptionRepositoryPort.promoteToHalfOpen`'s own javadoc (line 91,
  `/Users/jaideranillo/Documents/Cobre/challenge/src/main/java/com/cobre/challenge/application/port/out/persistence/SubscriptionRepositoryPort.java`):
  "Writes `circuit_state = 'HALF_OPEN'` only."
- ADR-006 §1.2 Amendment B1's transition table (line 90): the `OPEN -> HALF_OPEN` row's SET clause
  is `circuit_state = 'HALF_OPEN'`, and nothing else. The three other transitions in that table
  list every column they write explicitly; this one lists one column.

Found while implementing TASK-006-10's `RelayHalfOpenProbeAcceptanceTest` (working tree, not yet
committed). That test's scenario 1 diffs the full `subscriptions` column set after a promotion:
`circuit_opened_at`, `circuit_backoff` and `consecutive_opens` are all correct and unchanged;
`updated_at` is the only field that moved beyond `circuit_state`. The test is currently 2/3 passing
with 1 failure on exactly this assertion.

This is a one-method SQL correction to match contracts that already exist. It is **not** a design
question — do not open one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepository.java`
    (modified — `promoteToHalfOpen` only, the UPDATE's SET clause)
- Concern: make the adapter's SQL match its port contract and ADR-006 §1.2 Amendment B1.

Remove `updated_at = :as_of` from that one SET clause. The `:as_of` bind parameter itself stays —
the WHERE guard (`circuit_state = 'OPEN' AND circuit_opened_at < :as_of - circuit_backoff`) still
uses it, so the `MapSqlParameterSource` is unchanged. The guard, the return-`true`-on-one-row
semantics, and the method signature are all unchanged.

## Out of Scope

- **Any ADR file.** ADR-006 is already correct; the code was wrong. Do not edit, amend, or annotate
  it.
- **Any other method on `SubscriptionJdbcRepository`** — `openCircuit`, `reopenCircuit`,
  `closeCircuit`, and everything else keep their current SQL verbatim, including whatever they do
  with `updated_at`. If you believe one of those has the same class of mismatch, note it in
  `docs/concerns.md` and stop; it is a separate task.
- **The port interface.** Its javadoc already states the correct behavior; it needs no change.
- **`RelayHalfOpenProbeAcceptanceTest` itself** and every other test file. You run the test, you do
  not edit it. If it fails for a reason other than `updated_at`, that failure belongs to the task
  that owns the file it implicates (TASK-006-01, -06, -07 or -10) — say which in your handoff
  rather than adjusting the test to pass.
- The rest of FEAT-006's task chain. This task targets already-merged FEAT-004 code and has no
  dependency on, and is not depended on by, tasks 01-12.
- Any migration. No schema change is involved.

## Acceptance Criteria

- [ ] `promoteToHalfOpen`'s UPDATE sets `circuit_state` and no other column.
- [ ] The WHERE guard and the `:as_of` binding are byte-for-byte unchanged.
- [ ] `RelayHalfOpenProbeAcceptanceTest` (TASK-006-10's test, in the working tree) passes all
      scenarios — report the before (2/3) and after counts explicitly.
- [ ] `SubscriptionCircuitOpsTest` still passes — the FEAT-004 adapter test for these transitions.
- [ ] `./gradlew test` passes with no other test regressed.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if
      unavoidable). Note: removing a column from a SET clause changes no bind parameter and
      introduces no concatenation — A05 surface is unchanged.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
