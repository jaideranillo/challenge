---
id: TASK-007-17
feature: FEAT-007
title: DeliveryQueueListener — the SQS consumer loop
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-02, TASK-007-14, TASK-007-16]
date: 2026-09-21
---

# TASK-007-17: `DeliveryQueueListener`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryQueueListener.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/dto/PointerMessage.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/messaging/DeliveryQueueListenerTest.java` (new)
- Concern: receive, fan out to virtual threads, delete. Orchestration only — no business logic.

### Behavior (ADR-002 §2.2, ADR-006 §1.1)

```
loop while running:
  messages = sqsClient.receiveMessage(queueUrl,
                 waitTimeSeconds = 20,                 // challenge.worker.wait-time
                 maxNumberOfMessages = 10,             // challenge.worker.batch-size
                 messageAttributeNames = ["All"])
  for each message: submit to a virtual-thread executor:
      try {
          command = PointerMessage.toCommand(message)   // parse failure -> delete + alert log
          useCase.attempt(command)                      // returns after its transaction commits
          sqsClient.deleteMessage(...)                  // step 7, AFTER attempt returns
      } catch (Throwable t) {
          log at error by delivery_id; DO NOT delete.   // redelivery + maxReceiveCount + DLQ
      }
  await the batch's submissions before the next receive
```

### The rules this class exists to enforce

- **`DeleteMessage` on every business path.** A lost claim, a deferral, a dead delivery, a
  throttle — `attempt(...)` returns normally for all of them and the message is deleted. Only a
  thrown `Throwable` skips the delete, deliberately: that is the crash-loop signal
  `maxReceiveCount = 3` measures (ADR-002 §2.2's closing paragraph, ADR-004 §1).
- **Do not branch on the result.** Following TASK-007-14's follow-up 14a,
  `AttemptDeliveryResult.outcome()` is an `Optional` that is empty when no attempt was made. The
  listener still deletes in every case, so it must **not** read `outcome()` or `status()` to
  decide anything — "returned normally" is the whole condition. Log the result if useful
  (an empty `outcome` reads as "no attempt"), never switch on it. A listener that starts
  branching here is one refactor away from leaving a message undeleted on a deferral, which
  `maxReceiveCount = 3` would then turn into a spurious `FAILED` row.
- **`ChangeMessageVisibility` must not appear anywhere in this file.** ADR-006 §1.1 removed it
  from the design entirely, and `maxReceiveCount = 3` is only safe because of that. A reviewer
  should be able to grep for it and find nothing.
- **Delete strictly after `attempt(...)` returns.** Never before, never concurrently, never in a
  `finally` that would also fire on a throw. Deleting before the outcome commits would lose the
  attempt record and strand the row in `PROCESSING`.
- **One virtual thread per message**, from `Executors.newVirtualThreadPerTaskExecutor()` or by
  submitting to the application's virtual-thread executor. No platform-thread pool, no sizing
  knob, no `synchronized` around anything.
- **The loop never dies.** Catch `Throwable` around the receive as well as around each message;
  on a receive failure, log and back off briefly before the next iteration rather than spinning
  (A10 — a listener that exits silently stops all delivery).
- **Lifecycle:** start on `ApplicationReadyEvent` (or `SmartLifecycle`), stop cleanly on
  shutdown with a `volatile boolean` flag; no `Thread.stop`, no interrupt storm. Gate the whole
  bean on `@ConditionalOnProperty(prefix = "challenge.worker", name = "enabled",
  havingValue = "true", matchIfMissing = true)`, mirroring `DeliveryRelayScheduler`.
- **`PointerMessage`** is the inbound parse of the four-field envelope plus the `delivery_id` and
  `traceparent` message attributes: attribute first, body second (ADR-004 §1). A message that
  yields no `delivery_id` at all is deleted and raised as an operational alert — per ADR-004 §1
  it is a message this system did not produce, and it is deliberately not correlated to any row.
- **Logging:** `delivery_id`, `subscription_id`, attempt number, trace id. Never the body of the
  outbound webhook, never `content`, never a target URL or header.

## Out of Scope

- Everything inside `attempt(...)` (TASK-007-14).
- The DLQ consumer (TASK-007-18) — a separate loop on a separate queue.
- Autoscaling, KEDA, concurrency tuning.
- Any retry, requeue or visibility manipulation.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Mock `SqsClient` and `AttemptDeliveryUseCase`; drive a single iteration directly (expose
one package-private `pollOnce()` the loop calls, the way `DeliveryRelayScheduler` exposes its
cycle) rather than starting the real loop in a test.

Required unit scenarios:
- a normal outcome results in exactly one `deleteMessage` for that receipt handle
- a lost-claim result (`outcome()` empty, status `QUEUED`) also deletes
- a deferred result (`outcome()` empty, status `QUEUED`) also deletes
- the listener reads neither `outcome()` nor `status()` to decide whether to delete — assert by
  passing a result with an empty `outcome` and one with a present `outcome` and observing the
  same single `deleteMessage` call
- a thrown use case does **not** delete, and the exception does not escape `pollOnce`
- `deleteMessage` is never called before `attempt(...)` has returned (assert with an
  `InOrder` verification)
- `changeMessageVisibility` is never called on any path
- a message whose body and attributes yield no `delivery_id` is deleted, is not passed to the use
  case, and is logged at error
- the receive request carries `waitTimeSeconds = 20` and `maxNumberOfMessages = 10`
- a throwing `receiveMessage` does not propagate out of `pollOnce`

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.
The end-to-end poison-message-to-DLQ path is integration-only and is deferred with the phase —
do not attempt it here.

## Acceptance Criteria

- [ ] Long poll 20s and batch size 10, both read from `WorkerProperties`.
- [ ] One virtual thread per message; no platform-thread pool, no `synchronized`.
- [ ] `DeleteMessage` happens on every path where `attempt(...)` returns, and only after it returns.
- [ ] The delete decision reads no field of `AttemptDeliveryResult`; an empty and a present
      `outcome()` produce identical behavior.
- [ ] No occurrence of `ChangeMessageVisibility` anywhere in the file.
- [ ] A thrown `attempt(...)` leaves the message undeleted, by design, and is logged at error.
- [ ] The loop survives a receive failure and never exits on an exception.
- [ ] The bean is gated on `challenge.worker.enabled` and shuts down cleanly.
- [ ] `delivery_id` is read from the message attribute first, the body second.
- [ ] No payload, URL, header or secret in any log statement.
- [ ] Every unit scenario listed above is covered, including the `InOrder` ordering assertion.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
