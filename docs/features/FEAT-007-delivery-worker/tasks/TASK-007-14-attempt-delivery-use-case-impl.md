---
id: TASK-007-14
feature: FEAT-007
title: AttemptDeliveryUseCaseImpl — the per-message flow
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-06, TASK-007-07, TASK-007-08, TASK-007-09, TASK-007-10, TASK-007-11, TASK-007-12]
date: 2026-09-21
---

# TASK-007-14: `AttemptDeliveryUseCaseImpl`

## Follow-up 14a (2026-09-21): `AttemptDeliveryResult.outcome` becomes `Optional`

**Status: Ready for Review.** The implementation of this task correctly
refused to guess here and flagged the gap instead. This section is the decision.

### The problem, as found

`AttemptDeliveryResult(DeliveryStatus status, AttemptOutcome outcome)` has no way to say "no
attempt was made". Two paths need exactly that — a lost claim and a deferral (bulkhead or open
circuit) — and the implementation had to pick an `AttemptOutcome` value to satisfy the non-null
constructor: `NON_RETRYABLE` for claim-lost, `RETRYABLE` for deferred. Both are false. Nothing
was classified, because nothing was sent. A reader or a future metric that trusts that field is
being misled, and `RETRYABLE` on a deferral is the more dangerous of the two: it reads as
"the endpoint failed", which is precisely the thing ADR-006 §1.3 insists a deferral is not.

### The decision

```java
public record AttemptDeliveryResult(DeliveryStatus status, Optional<AttemptOutcome> outcome)
```

`outcome` is **empty if and only if no HTTP attempt was made** — lost claim, bulkhead deferral,
open-circuit deferral. Present in every other case, including every pre-HTTP classification the
egress validator produces (TASK-007-20), because those *are* real classifications of a real
decision about a real target.

Add two static factories so no call site ever writes `Optional.of(...)` by hand or, worse,
`Optional.ofNullable(...)`:

```java
static AttemptDeliveryResult noAttempt(DeliveryStatus status)                     // outcome empty
static AttemptDeliveryResult of(DeliveryStatus status, AttemptOutcome outcome)    // outcome present
```

Keep the compact constructor's null checks; `outcome` must be a non-null `Optional`.

**Why `Optional` and not a `boolean attempted` flag.** The flag leaves the illegal state
representable: `attempted = false` alongside a populated `outcome` is exactly today's lie, now
with a contradicting field next to it, and nothing stops a caller reading the outcome anyway.
`Optional.empty()` makes the absence unrepresentable-as-a-value — you cannot read a classification
that was never made. It is also what this codebase already does everywhere else a value may be
absent (`WebhookResponse.retryAfter`, `Delivery.nextAttemptAt`, `DeliveryAttempt.httpStatus`),
and Effective Java Item 55's case for `Optional` on a return value applies directly.

**`status` stays non-null and stays `DeliveryStatus.QUEUED` on both no-attempt paths**, with its
javadoc tightened to say what it now means: *the status this call left the row in; `QUEUED` when
this call changed nothing*. That is exactly true of a deferral (`deferDelivery` is guarded on
`status = 'QUEUED'` and writes only `next_attempt_at`). For a lost claim the row belongs to
another worker and this call changed nothing, which is the same statement. Widening `status` to
`Optional` as well was considered and rejected: it buys no caller anything, and the listener
(TASK-007-17) does not branch on status at all.

**This is a narrow widening of one field on one `port/in` DTO.** It adds no `AttemptOutcome`
value — that constraint from the earlier direction still holds and is untouched. It adds no
`DeliveryStatus` value, no column and no port method.

### Scope of the follow-up

- `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/AttemptDeliveryResult.java`
  (modified — the component type, the two factories, the tightened javadoc)
- `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java`
  (modified — the `CLAIM_LOST` and `DEFERRED` constants become `noAttempt(QUEUED)`; every other
  return becomes `of(status, outcome)`)
- `src/test/java/com/cobre/challenge/application/port/in/pipeline/PipelineUseCasePortsTest.java`
  (modified — its one construction site at line 62)

### Acceptance criteria for the follow-up

- [ ] `outcome` is `Optional<AttemptOutcome>`; the compact constructor rejects a null `Optional`.
- [ ] `noAttempt` and `of` exist and are the only construction sites used in production code.
- [ ] Both no-attempt paths return `noAttempt(DeliveryStatus.QUEUED)`; **no `AttemptOutcome`
      value is fabricated anywhere in the class**.
- [ ] Every attempted path — including the two egress-validator classifications — returns a
      present outcome.
- [ ] No new `AttemptOutcome` or `DeliveryStatus` value, no column, no port method.
- [ ] `PipelineUseCasePortsTest` compiles and still asserts the port shape it was written for.
- [ ] Unit tests only, and **do not run** `./gradlew test` or `build`; verify with
      `./gradlew compileJava compileTestJava`.

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java` (new)
- Concern: ADR-002 §2.2 steps 1-6, in order, once per message. One file, because splitting the
  ordering across classes is what makes it get reordered.

## The flow — this order is fixed and must not be changed

```
attempt(AttemptDeliveryCommand command) -> AttemptDeliveryResult

1. claimed = pipelinePort.claimForProcessing(command.deliveryId(), now)
   if (!claimed) -> return a CLAIM_LOST result immediately.        // zero rows: duplicate/race
                                                                    // caller deletes the message
2. delivery     = pipelinePort.findById(...)      (absent -> same short-circuit)
   subscription = subscriptionPort.findById(...)  (absent -> same short-circuit)
   event        = eventPort.findById(delivery.eventId())
   if (subscription.circuitState() == CLOSED) circuitBreakerPort.resetIfOpenLocally(subId);

3. if (!bulkheadPort.tryAcquire(subId, subscription.maxConcurrency(), acquireTimeout)):
        pipelinePort.deferDelivery(deliveryId, now + jitter(deferMin, deferMax));
        return a DEFERRED result.                                   // no attempt row,
                                                                    // no attempt_count change
   try { steps 4-6 } finally { bulkheadPort.release(subId); }

4. if (subscription.circuitState() == OPEN):    defer exactly as in step 3, return DEFERRED.
   boolean isProbe = subscription.circuitState() == HALF_OPEN;

5. // TASK-007-20 inserts the egress pre-flight here, immediately before the POST:
   //   urlValidator.validate(targetUrl) -> ALLOWED proceeds; DNS_FAILURE -> RETRYABLE;
   //   POLICY_REJECTED -> the existing NON_RETRYABLE, no POST, no breaker call.
   // Leave the seam clean (the send is a single, easily-guarded call site); do not
   // implement the validation yourself and do not add an AttemptOutcome value for it.
   body      = serializerPort.serialize(envelope(delivery, event, attemptNumber));
   timestamp = ISO-8601 instant of now;
   secret    = secretPort.resolve(subscription.secretRef())
                 -> empty: no POST. Record a NON_RETRYABLE outcome through the writer
                    (last_error "secret unresolved") and return. Fail closed, never send unsigned.
   previous  = subscription.previousSecretRef() present AND previousSecretExpiresAt after now
                 ? secretPort.resolve(...) : Optional.empty();
   headers   = WebhookSigner.sign(body, timestamp, secret, previous)
                 + X-Cobre-Timestamp + X-Cobre-Delivery-Id;
   response  = webhookClientPort.send(new WebhookRequest(subscription.targetUrl(), body, headers));

6. outcome = ResponseClassifier.classify(response.statusCode(), response.failure());
   if (outcome.countsTowardCircuitBreaker() && !isProbe && subscription.circuitState() == CLOSED):
        if (circuitBreakerPort.recordFailure(subId))                 // the TRIP EDGE, once
             subscriptionPort.tripCircuit(subId, base, max, now);
   if (outcome == SUCCESS) circuitBreakerPort.recordSuccess(subId);
   outcomeWriter.write(new AttemptOutcomeCommand(..., wasHalfOpenProbe = isProbe, ...));
   return new AttemptDeliveryResult(resultingStatus, outcome);
```

Step 7 (`DeleteMessage`) is **not** in this class: the listener (TASK-007-17) deletes after this
method returns, which is how "state commits before the delete" is enforced structurally rather
than remembered. This method must therefore **return normally on every business outcome** —
including a lost claim, a deferral and a dead delivery — and throw only on a genuine
infrastructure failure the listener should not delete on.

## Rules that are easy to get wrong

- **No `@Transactional` on this class.** The only transaction is `DeliveryOutcomeWriter`'s, and it
  must be closed before the method returns. A transaction spanning the HTTP call would hold a
  connection for up to 7 seconds per attempt.
- **`tripCircuit` is called on the edge only**, from `recordFailure` returning `true`. Never call
  it on every failure, never call it while the row already says `OPEN` or `HALF_OPEN` — the SQL
  guard would absorb it, but issuing the statement at all defeats ADR-006 §1.2's hot-row rule.
- **A probe's circuit transition is the writer's**, inside step 6's transaction (`closeCircuit` /
  `reopenCircuit`). Do not also call them here — that would be two writes and a race with itself.
- **A 429 never reaches `recordFailure`.** `countsTowardCircuitBreaker()` is the gate; read it.
- **Bulkhead release in a `finally`**, always, including on a thrown HTTP adapter (which should
  not happen — the adapter returns failures — but the `finally` is the guarantee, not the hope).
- **Jitter** for the deferral is a uniform draw over `[deferMin, deferMax]` from an injected
  `RandomGenerator`, the same shape the merged `RetryPolicy` uses. Not `Math.random()`.
- **`now` comes from an injected `Clock`** (or a single `Instant` captured at entry) so the whole
  attempt uses one instant and a test can pin it. No scattered `Instant.now()` calls.
- **Attempt number** is `delivery.attemptCount() + 1`, from the row — never from the message's
  `attemptHint`, which ADR-004 §1 marks advisory only.
- **Logging (ADR-002 §3/§3.1):** structured, `delivery_id` / `subscription_id` / attempt number /
  status class / trace id. **Never** `content`, the response body, the target URL, a header, a
  signature or a secret. Restore the traceparent from `command.traceparent()`, falling back to
  `delivery.traceContext()`, and clear MDC in a `finally` so nothing leaks across carrier reuse.
- **Counters (ADR-002 §3):** attempt outcomes by status class, bulkhead deferrals, circuit
  transitions by direction, zero-row claims. **No `client_id` or `subscription_id` tag on any
  meter** — Q8 forbids it outright.
- **No `synchronized`, no `ThreadLocal` across the HTTP call, no executor of its own.**

## Out of Scope

- The unit tests — TASK-007-15, so this task stays at one file.
- `DeleteMessage`, receiving, and the poll loop (TASK-007-17).
- The egress validation itself — TASK-007-19 builds it and TASK-007-20 wires it into the seam
  marked in step 5. Keep that call site a single guarded statement so the wiring is a small,
  reviewable diff.
- Any new or widened port method. Every collaborator already exists after TASK-007-06..-12.
- Re-implementing classification, backoff or the cooldown formula.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. **Write no test here** — TASK-007-15 owns them. Your obligation is testability:
constructor injection for all nine collaborators, an injected `Clock` and `RandomGenerator`, no
static state, no `new` of a collaborator inside a method.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava` and
report the class as compiling, with tests pending TASK-007-15 and the Tech Lead's later run.

## Acceptance Criteria

- [ ] Implements `AttemptDeliveryUseCase` with no signature change.
- [ ] The seven-step order above is implemented exactly, with no step reordered or merged.
- [ ] No `@Transactional` anywhere in the class; step 6 is delegated to `DeliveryOutcomeWriter`.
- [ ] A zero-row claim returns without loading, signing, or calling the webhook.
- [ ] Bulkhead rejection calls `deferDelivery` with a jittered instant in `[now+10s, now+20s]`
      and makes no attempt-row or `attempt_count` write.
- [ ] An `OPEN` circuit defers by the same path; `HALF_OPEN` proceeds as the probe.
- [ ] `tripCircuit` is issued only on `recordFailure`'s trip edge, at most once per attempt.
- [ ] Probe circuit transitions are left to the writer's transaction.
- [ ] An unresolvable secret fails closed: no request is sent, and the delivery is recorded as a
      non-retryable failure.
- [ ] The permit is released in a `finally`.
- [ ] One captured instant per attempt; no scattered `Instant.now()`.
- [ ] Attempt number comes from the row, not from `attemptHint`.
- [ ] No payload, URL, header, signature or secret in any log statement; MDC cleared in `finally`.
- [ ] No meter carries a `client_id` or `subscription_id` tag.
- [ ] No `synchronized`, no `ThreadLocal` held across the HTTP call, no mutable field.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] A01-SSRF is still open and is restated in the handover for TASK-007-19.

## Definition of Done

Code written; tests are TASK-007-15's. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
