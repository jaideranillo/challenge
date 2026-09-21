---
id: TASK-007-12
feature: FEAT-007
title: DeliveryOutcomeWriter — step 6's single transaction
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-03]
date: 2026-09-21
---

# TASK-007-12: `DeliveryOutcomeWriter`

## Addendum 12a (2026-09-21): an explicit `error` on the command wins over the derived one

**Status: Ready for Review, assigned to `backend-engineer`.** Raised by TASK-007-20's implementation.

`attemptErrorFor(...)` derives `last_error` and `delivery_attempts.error` from the outcome and
the transport failure, which is right for an HTTP exchange. It is useless for a pre-flight
rejection: the row ends up saying `"NON_RETRYABLE"`, when the validator knew and said
`"egress: target resolves to a blocked range"`.

**Decision: fix it, rather than accept the log as sufficient.** The warn log does carry the
reason, but a log line and an audit row answer different questions and have different lifetimes.
`delivery_attempts` is the table ADR-004 §1 justifies by "you never called me at 14:02" being
answerable — and `"NON_RETRYABLE"` does not answer it. Logs roll over; this row is the permanent
record, and it is the one a support engineer reaches first. The fix is a few lines and there is
no argument for carrying a known-worse string into an append-only audit table.

`AttemptOutcomeCommand` already has an `error` component. Make the writer **use it when it is
present and non-blank**, and fall back to `attemptErrorFor(...)` only when it is absent. Both the
`delivery_attempts.error` insert and the `last_error` passed to `scheduleRetry`/`markDead` take
the same resolved value, so the row and its history agree.

The existing rules still hold without exception: whatever ends up in that column is short and
carries no response body, no URL path, no header and no secret. The validator's reason strings
are already constrained that way by TASK-007-19; a caller that supplies something longer is the
caller's defect, so cap the persisted value at the column's limit rather than trusting it.

### Acceptance criteria for 12a

- [ ] A command carrying a non-blank `error` persists that string, unchanged, to both
      `delivery_attempts.error` and `last_error`.
- [ ] A command with an absent or blank `error` falls back to the derived value exactly as today.
- [ ] The persisted value is truncated to the column's bound.
- [ ] Unit tests cover both branches, including the egress-rejection shape from TASK-007-13's
      scenario 12.
- [ ] No port signature and no column changes.

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/DeliveryOutcomeWriter.java` (new)
  - `src/main/java/com/cobre/challenge/application/usecase/dto/AttemptOutcomeCommand.java` (new)
- Concern: ADR-002 §2.2 step 6 — the attempt row, the delivery row and (for a probe) the circuit
  transition, in **one** transaction. No HTTP, no queue, no classification.

### Why a separate bean

Spring's `@Transactional` is proxy-based: a transactional method called on `this` from inside the
same bean runs with no transaction at all. Step 6 must commit as a unit — ADR-006 §1.2: "a crash
between 'delivery recorded' and 'circuit updated' is impossible". Same split, same reason, as
FEAT-006's `RelayBatchClaimer`.

### Input

`AttemptOutcomeCommand` (record, `application/usecase/dto`): the already-classified facts.

```
deliveryId, subscriptionId, attemptNumber, attemptedAt,
outcome (AttemptOutcome), httpStatus (OptionalInt), transportFailure,
responseTimeMs, responseExcerpt (Optional<String>), retryAfter (Optional<Duration>),
wasHalfOpenProbe (boolean), currentAttemptCount
```

**Nothing here re-classifies.** `AttemptOutcome` arrives already computed — by the merged
`ResponseClassifier` for an HTTP exchange, or by the use case's egress pre-flight when no
exchange happened (TASK-007-20). This class switches on it.

**A command can legitimately carry no `httpStatus`.** ADR-003 §3 already models a failure that
occurred before any HTTP exchange as `error` populated with `http_status` absent, and two paths
produce exactly that: a transport failure, and an egress policy rejection (`NON_RETRYABLE`) or
DNS failure (`RETRYABLE`) from the validator. Insert the `delivery_attempts` row with
`OptionalInt.empty()` for the status and the given `error`. **No new column, no new enum, no
special-casing beyond honoring the absent status** — the existing `NON_RETRYABLE` branch already
does the right thing with it (`markDead`), which is precisely why no new outcome value exists.

### The one transactional method

`@Transactional void write(AttemptOutcomeCommand command)` — the only annotation in the file.

```
1. deliveryAttemptRepositoryPort.insert(new DeliveryAttempt(...))     // always, append-only
2. switch (command.outcome()):
     SUCCESS                              -> markDelivered(deliveryId, attemptedAt)
     RETRYABLE                            -> retryOrDie(...)
     RETRYABLE_THROTTLED                  -> retryOrDie(...) and setThrottledUntil(...)
     NON_RETRYABLE                        -> markDead(deliveryId, lastError, now)
     NON_RETRYABLE_REDIRECT               -> markDead(...)
     NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION-> markDead(...) and deactivate(subscriptionId)
3. if (command.wasHalfOpenProbe()):
     outcome == SUCCESS                   -> closeCircuit(subscriptionId, now)
     outcome.countsTowardCircuitBreaker() -> reopenCircuit(subscriptionId, base, max, now)
     otherwise                            -> no subscriptions circuit write
```

`retryOrDie`: ask the injected `RetryPolicy` for `nextAttemptAt(currentAttemptCount + 1, now)`.
Present -> `scheduleRetry(deliveryId, thatInstant, lastError, now)`. Empty (the schedule is
exhausted, ADR-004 §1 "reaching `max_attempts` moves the row to `DEAD`") -> `markDead(...)`.

**429 specifically** (`RETRYABLE_THROTTLED`): the row is scheduled for retry **and**
`setThrottledUntil` is called. `throttled_until = attemptedAt + retryAfter` when the command
carries a `Retry-After`, otherwise the computed `nextAttemptAt`. The value arriving on the
command is **already parsed and already clamped** to the profile's `retry-after-max` by
`WebhookResponseMapper` (TASK-007-05) — an absent value means "unusable, use the schedule", so
this class neither re-validates nor re-clamps it, and must not treat its absence as an error. A 429 must never reach
step 3's `reopenCircuit` branch — `countsTowardCircuitBreaker()` already returns `false` for it,
so read that method rather than special-casing the status code.

**A `CLOSED`-circuit attempt writes nothing to `subscriptions`** unless it is a 429 or a 404/410.
That is ADR-006 §1.2's hot-row rule and it is the easiest thing in this class to get wrong.

Other rules:
- `lastError` is a short, non-PII description: the status code or the `TransportFailure` name.
  **Never** the response body, never the URL, never a header.
- Every port here returns `boolean` for a conditional write. A `false` is a lost race, not an
  error: log at debug and continue. **Do not throw**, and do not retry the write in a loop.
- `now`/`attemptedAt` arrive on the command. No `Instant.now()` inside this class.
- Base and max cooldown come from `WorkerProperties`; pass them through to `reopenCircuit`.

## Out of Scope

- The unit tests — TASK-007-13, deliberately split so this task stays at two files.
- Claiming, the HTTP call, signing, the bulkhead, the breaker's in-memory count and
  `tripCircuit` (the trip is *not* a probe outcome; TASK-007-14 owns it).
- `DeleteMessage` and everything SQS.
- Any new port method. If a write seems inexpressible, log it in `docs/concerns.md` and stop.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. **Write no test in this task** — TASK-007-13 carries the whole response-code matrix. Your
obligation here is to make it unit-testable: constructor injection only, every collaborator a
port interface, no static clock, no `new` of a collaborator inside a method.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava` and
report the class as compiling, with tests pending TASK-007-13 and the Tech Lead's later run.

## Acceptance Criteria

- [ ] Exactly one `@Transactional` method; no other Spring annotation beyond `@Component`.
- [ ] The attempt row is inserted before the status update, in the same transaction.
- [ ] Probe circuit transitions happen in that same transaction, guarded by `wasHalfOpenProbe`.
- [ ] A non-probe attempt on a `CLOSED` circuit writes nothing to `subscriptions` except for a
      429 (`setThrottledUntil`) or a 404/410 (`deactivate`).
- [ ] 429 both schedules a retry and sets `throttled_until`, and never calls `reopenCircuit`.
- [ ] An exhausted retry schedule produces `markDead`, not `scheduleRetry`.
- [ ] `AttemptOutcome.countsTowardCircuitBreaker()` is read, never re-implemented.
- [ ] `lastError` contains no body, URL, header or secret.
- [ ] A `false` from any conditional write is tolerated, logged at debug, and never thrown.
- [ ] No `Instant.now()`, no `synchronized`, no mutable field.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written; tests are TASK-007-13's. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
