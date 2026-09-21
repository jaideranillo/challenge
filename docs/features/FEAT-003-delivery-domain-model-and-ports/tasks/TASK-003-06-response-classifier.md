---
id: TASK-003-06
feature: FEAT-003
title: AttemptOutcome enum and pure ResponseClassifier
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-01]
date: 2026-09-20
---

# TASK-003-06: AttemptOutcome enum and pure ResponseClassifier

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The pure classification function of ADR-004 §1, and the outcome vocabulary it returns.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/policy/AttemptOutcome.java` (new)
  - `src/main/java/com/cobre/challenge/domain/policy/TransportFailure.java` (new)
  - `src/main/java/com/cobre/challenge/domain/policy/ResponseClassifier.java` (new)
- Concern: turning an attempt's raw result into a domain outcome.

**Signature (fixed here, do not vary it):**

```
AttemptOutcome classify(int statusCode, TransportFailure failure)
```

`statusCode` is a primitive `int`. `TransportFailure` is a domain enum: `NONE, TIMEOUT, CONNECTION_RESET, DNS_FAILURE, TLS_FAILURE`. When `failure != NONE` the `statusCode` argument is ignored (no response was received); the caller passes `0`. **No `HttpStatus`, no `ResponseEntity`, no `HttpResponse`, no exception type from any client library appears in this file** — mapping a real exception onto `TransportFailure` is the outbound adapter's job in a later feature.

`AttemptOutcome` values, each carrying `boolean countsTowardCircuitBreaker()` per ADR-004 §1's third column:

| Value | Resulting delivery state | Counts toward breaker |
| --- | --- | --- |
| `SUCCESS` | `DELIVERED` | no |
| `RETRYABLE` | `RETRYING` | yes |
| `RETRYABLE_THROTTLED` | `RETRYING`, plus `throttled_until` on the subscription | no |
| `NON_RETRYABLE` | `DEAD` | no |
| `NON_RETRYABLE_REDIRECT` | `DEAD`, not followed | yes |
| `NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION` | `DEAD`, plus `active = false` | no |

**The classification table, verbatim from ADR-004 §1. Every row must be implemented:**

| Input | Result |
| --- | --- |
| 2xx (200-299) | `SUCCESS` |
| 3xx (300-399) | `NON_RETRYABLE_REDIRECT` (never followed, SSRF; counts toward breaker) |
| 400, 422 | `NON_RETRYABLE` |
| 401, 403 | `NON_RETRYABLE` |
| 404, 410 | `NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION` |
| 408 | `RETRYABLE` |
| 429 | `RETRYABLE_THROTTLED` |
| 5xx (500-599) | `RETRYABLE` |
| `TransportFailure` other than `NONE` (timeout, connection reset, DNS, TLS) | `RETRYABLE` |

Any other 4xx not named above (e.g. 418, 451) is `NON_RETRYABLE`: ADR-004 §1's non-2xx/non-408/non-429 4xx rows are all permanent/business failures, so the 4xx default follows them. State that reasoning in a comment so a reader knows it is a deliberate default and not a gap.

A status code outside 100-599 with `failure == NONE` is a caller bug: throw `IllegalArgumentException`. **There is no silent fallback outcome** — ADR-003's A10 row requires ambiguity to fail explicitly rather than being swallowed.

`ResponseClassifier` is stateless. Either a final class with a private constructor and a static method, or an enum-singleton; do not make it a Spring bean and do not give it fields.

## Out of Scope

- No `Retry-After` parsing. ADR-004 §1 says a 429 honors `Retry-After` "when present and sane"; parsing a header value is adapter work and the `throttled_until` arithmetic is the use case's. This function returns `RETRYABLE_THROTTLED` and nothing more.
- No `Delivery` mutation — the classifier returns an outcome, it does not apply it.
- No backoff computation — TASK-003-08.
- No breaker state transition — that is ADR-006 §1.2, a later feature's infrastructure.
- No tests in this task — TASK-003-07 owns them.

## Acceptance Criteria

- [ ] The method signature is exactly `classify(int, TransportFailure)` returning `AttemptOutcome`.
- [ ] Every row of the table above is implemented, 3xx, 408 and 429 included, each distinguishable from the others in the returned value.
- [ ] `countsTowardCircuitBreaker()` matches ADR-004 §1's third column for all six outcomes, including the two non-obvious cases: 3xx counts, 429 does not.
- [ ] Out-of-range status with `NONE` failure throws `IllegalArgumentException`; no branch returns a default outcome silently.
- [ ] No import outside `java.*`. Grep the file for `springframework`, `jakarta`, `http` client types — none may appear.
- [ ] Tests written and passing (full table coverage lands in TASK-003-07; the build must be green here).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced — this file is the A10 control surface (ADR-003's OWASP row) and A01-SSRF's "3xx is never followed" decision; neither may be weakened.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
