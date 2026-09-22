---
id: TASK-008-30
feature: FEAT-008
title: Security review of the assembled self-service API chain
status: Not Started
agent: security-engineer
depends_on: [TASK-008-28, TASK-008-29]
date: 2026-09-21
---

# TASK-008-30: Security Review of the Assembled Chain

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Phase note

This review reads code, not a running system. **It can be performed as soon as TASK-008-01
through TASK-008-27 are `Ready for Review`**, without waiting for the deferred Testcontainers
suite — and it should be, because a finding is cheaper before the integration tests are written
against the behavior.

Its `depends_on` names 28 and 29 because the review is not **complete** until it has also
confirmed those suites assert what they claim. Do the code review now; record the two
test-verification items as outstanding and tied to the deferral.

## Scope

- File(s): **none produced by this task except the report.** This is a read-and-report task.
  Findings become new tasks; they are not fixed here.
- Concern: does the assembled chain actually deliver ADR-007's four structural properties.

## The four properties to verify, one by one (ADR-007 §1)

The ADR claims these hold **by construction rather than by discipline**. Verify each against the
merged code and say, per property, what specifically makes it true:

1. **No endpoint is reachable without a valid token**, because the terminal chain is
   `anyRequest().denyAll()` and each permitted path is listed explicitly.
2. **No use case can query client-owned data without a `TenantId`**, because no such method
   signature exists.
3. **No `TenantId` can be manufactured from request input**, because the only production factory
   reads the `Authentication`.
4. **No SQL can return another tenant's rows under the API's database role**, because the RLS
   policy filters them before the query sees them.

For each, name the file and the line that makes it true, and name what would break it.

## The specific things most likely to be wrong

Check each explicitly; these are the failure modes ADR-007 itself predicts:

- **The API path wired to the pipeline pool**, silently disabling RLS. Confirm every client-facing
  adapter takes the qualified API template and that the pipeline adapters do not.
- **`challenge_api` holding `BYPASSRLS` or owning a table**, which turns every policy into
  decoration.
- **A `SET LOCAL` that never runs**, leaving the variable unset. Confirm this fails closed (zero
  rows) rather than open, on every path.
- **A tenant value interpolated into SQL text** anywhere, particularly the session-configuration
  statement (A05).
- **`SCOPE_` prefix drift** between the token, the converter and the authorization rules.
- **A `permitAll()`** outside the three health probes and the `local`-profile stub chain.
- **The deleted `defaultFilterChain`** — confirm it is gone and that no path lost its protection
  when it went.
- **A `synchronized` block enclosing I/O** in the rate limiter or the idempotency guard — the
  virtual-thread pinning risk in this feature.
- **Anything logging a token, a signature, a key or a claim beyond `sub`/`client_id`** (A09).
- **A 401 body that differs between failure causes**, or a 403 where ADR-007 §5.5 requires 404.
- **Any new dependency** beyond the two TASK-008-01 added (A03).
- **Any committed secret** other than the deliberately worthless dev key, and confirm that key is
  outside `src/main/resources`.

## Deferred verification, to be completed when the Testcontainers suite is implemented

- TASK-008-28's suite actually runs through the real chain and the `challenge_api` role — not a
  security-bypassing test slice, not the owner connection.
- TASK-008-29's pagination test asserts at-most-once for concurrently inserted rows rather than
  asserting presence, which would flake and then be weakened.

## Out of Scope

- Fixing anything. A finding becomes a new task with an owner; this task does not edit production
  code.
- SSRF / egress controls (FEAT-007, already shipped).
- Producer SigV4 verification — reserved, not designed (ADR-007 §8).
- Webhook signing and secret rotation.
- Subscription-management authorization, which has no API (Q9).

## Acceptance Criteria

- [ ] Each of the four structural properties is verified, with the file and line that delivers it
      named, and with what would break it named.
- [ ] Every item in "most likely to be wrong" is checked and explicitly reported as pass or
      finding — no silent omissions.
- [ ] Every finding is written up with: severity, the OWASP Top 10:2025 category, the file, and a
      proposed owning agent for the fix.
- [ ] Findings are **not** fixed in this task.
- [ ] The two deferred verification items are recorded as outstanding and tied to the
      Testcontainers deferral.
- [ ] The report states plainly whether ADR-007's "uncompilable or unreachable" bar is met, and if
      not, exactly where the gap is.
- [ ] Anything that deviates from ADR text is logged in `docs/concerns.md` per CLAUDE.md's design
      authority rule, rather than reopening the ADR.

## Definition of Done

Review performed and reported. **Do not run `git add` or `git commit`.** Set this task's `status`
to `Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
