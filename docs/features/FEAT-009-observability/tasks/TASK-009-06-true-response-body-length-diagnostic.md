---
id: TASK-009-06
feature: FEAT-009
title: Carry the untruncated response body length across the webhook port so response_body_length stops saturating at the excerpt limit
status: Not Started
agent: backend-engineer
depends_on: [TASK-009-02]
date: 2026-09-21
---

# TASK-009-06: True `response_body_length` Diagnostic

## Feature

FEAT-009

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Why this task exists

Architect ruling on TASK-009-02's review, 2026-09-21. **This is an architect error in TASK-009-02's
own scope table, not an implementer deviation.**

ADR-008 §3.3 substitutes `response_body_length` for the forbidden `response_excerpt`, and
TASK-009-02's table claimed it was "already available". It is not. The only length crossing the
`WebhookClientPort` boundary is `WebhookResponse.responseExcerpt()`, which
`JdkWebhookClientAdapter` has **already truncated** to `challenge.worker.response-excerpt-limit`
(1024). TASK-009-02 therefore derives `response_body_length` from the truncated excerpt, which is
the only thing it could have done inside its file scope.

The result is honest but **saturating**: every response body of 1024 characters or more logs
`response_body_length=1024`. ADR-008 §3.3 states the three questions the length exists to answer —
*"did the body change, was it empty, was it enormous"* — and a saturating length answers the first
two and silently fails the third, which is the one an operator debugging a misbehaving client
subscriber most wants. A reader cannot distinguish a 1KB response from a 4MB one.

The true length **is** available, in one place: `JdkWebhookClientAdapter` holds the full
`HttpResponse<String>` body before it truncates. It simply never carried it across the port. One
component on `WebhookResponse` closes this.

**Units, resolved here rather than by reopening the ADR.** ADR-008 §3.3 says "bytes".
`String::length` is UTF-16 code units, which coincides with bytes only for ASCII. The diagnostic is
specified for this task as the **character length of the untruncated decoded body**: a byte length
would require re-encoding an already-decoded `String` on every attempt, a real per-attempt cost for
no gain against any of the three questions the ADR names. `content_length` is already the character
length of `event.content()` for the same reason. Both are documented as characters at the logging
site. This is an implementation-level clarification of an ADR diagnostic, not a design change; it
reopens nothing in ADR-008.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/dto/WebhookResponse.java`
    (modified — one new component carrying the untruncated body length)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/JdkWebhookClientAdapter.java` (modified —
    populates it from the body it already has, before truncating)
  - `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java`
    (modified — `recordAndReturn` logs the true length instead of the excerpt's)
- Concern: one log diagnostic telling the truth about its own bound.

## What to change

### 1. `WebhookResponse` grows the untruncated length

Add a single `int responseBodyLength` component: the character length of the **untruncated** decoded
response body, `0` when there was no body and `0` on a transport failure that produced no response
at all. An `int`, not an `OptionalInt` — a body that does not exist has length zero, which is a
meaningful and unambiguous value, and `OptionalInt` here would be ceremony (YAGNI, and the absent
case is not semantically distinct from empty for this diagnostic).

Validate it non-negative in the compact constructor, matching the record's existing style.

`responseExcerpt` keeps its current meaning and its truncation exactly as they are. **Do not change
`challenge.worker.response-excerpt-limit`, the truncation, or the `response_excerpt` column** — the
database keeps storing the truncated excerpt; this task only stops the *log line* from lying about
how long the body was.

The existing `toString()` override stays as TASK-009-02 wrote it (redaction marker plus length for
`responseExcerpt`). Print the new component as a plain integer — it is a length, not a payload.

### 2. `JdkWebhookClientAdapter` populates it

Both `WebhookResponse` construction sites in this adapter get the new component:

- the success path in `toResponse`: the full body's length, read **before** truncation;
- the `catch (Exception e)` transport-failure path: `0`.

This adapter is the only place the untruncated body exists, which is exactly why the value has to
originate here. The `notification.delivery.attempt.latency` timer, its tags, and `statusClassOf`
are TASK-009-02's and are **not** touched.

### 3. `AttemptDeliveryUseCaseImpl` logs it

`recordAndReturn` currently derives `responseBodyLength` from
`outcomeCommand.responseExcerpt().map(String::length)`. Replace that with the true length, carried
in the same way `contentLength` already is: as a parameter to `recordAndReturn`, passed `0` from the
secret-unresolved and egress-rejection paths (no HTTP call was made, so there is no body) and the
`WebhookResponse`'s value from the HTTP path.

Do **not** widen `AttemptOutcomeCommand` for this. That record is the persistence-facing write
command and the untruncated length is not persisted; adding a field there would put a log-only
diagnostic into a write contract (SRP). `recordAndReturn` already takes `contentLength` as a
parameter for exactly this reason — follow that precedent.

The log line's shape is otherwise unchanged and still carries no `content` and no
`response_excerpt`.

## Out of Scope

- **`AttemptDeliveryResult`, any span attribute, any MDC key, and both latency timers** —
  TASK-009-02 delivered those and they are correct. `response_body_length` is a **log** field only:
  it must not become a meter tag or a span attribute (ADR-008 §4.3 — and a length is a
  high-cardinality integer).
- The `response_excerpt` column, the truncation, and `challenge.worker.response-excerpt-limit`.
- Any `toString()` redaction change — TASK-009-02's are correct.
- `content_length`, which is already the untruncated `event.content()` length and needs nothing.
- Any `application*.yaml` property — TASK-009-03.
- Persisting the untruncated length. No migration, no column. It is a log diagnostic with no reader
  beyond Loki; a column for it is YAGNI and would need a DBA task.

## Acceptance Criteria

- [ ] `WebhookResponse` carries the untruncated response body length as a non-negative `int`,
      validated in its compact constructor, and stays an immutable record.
- [ ] `JdkWebhookClientAdapter` populates it from the full body **before** truncation on the success
      path and `0` on the transport-failure path.
- [ ] `response_body_length` in `AttemptDeliveryUseCaseImpl`'s attempt-outcome log line is the
      untruncated length and **no longer saturates** at the excerpt limit — a body longer than the
      limit logs its real length.
- [ ] `AttemptOutcomeCommand` is **not** widened; the length reaches the log line as a
      `recordAndReturn` parameter, the way `contentLength` already does.
- [ ] Neither `content` nor `response_excerpt`, nor any prefix or substring of either, reaches any
      logger call, at any level including `DEBUG` and `TRACE`.
- [ ] `response_body_length` appears on **no** meter tag and **no** span attribute (ADR-008 §4.3).
- [ ] The `response_excerpt` truncation, its property and its column are unchanged.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A09 — a length is not a payload, and this replaces a
      misleading diagnostic with an accurate one; the redaction rule is unchanged and unweakened).

## Verification for this task

`./gradlew compileJava compileTestJava`. **Do not run `./gradlew test` or `./gradlew build`** —
FEAT-009's single full build is TASK-009-04's.

**No unit tests.** FEAT-009 runs under an explicit Tech Lead override suspending unit tests and
Testcontainers work for the whole feature. Do not write them and do not ask. Note that adding a
component to `WebhookResponse` will break existing test-source construction sites; fixing those call
sites to compile is in scope, writing new tests is not.

## Definition of Done

Code written, compiles clean. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
