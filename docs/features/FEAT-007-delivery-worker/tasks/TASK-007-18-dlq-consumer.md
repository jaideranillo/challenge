---
id: TASK-007-18
feature: FEAT-007
title: DeliveryDlqConsumer — correlate a poison message and mark the row FAILED
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-02, TASK-007-16]
date: 2026-09-21
---

# TASK-007-18: `DeliveryDlqConsumer`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryDlqConsumer.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/messaging/DeliveryDlqConsumerTest.java` (new)
- Concern: the dedicated DLQ-consumer actor of ADR-003 §1.1 / ADR-004 §1. Correlate, write
  `FAILED`, delete, alert.

### Why `FAILED` and not `DEAD`

ADR-004 §1 draws the line: `DEAD` is a business outcome — the webhook was attempted and
definitively failed, and it is replayable. `FAILED` means the consumer itself could not process
the pointer, so the row is visible as an application bug rather than as ordinary backlog, and
`POST /replay` refuses it (ADR-005 §1). `markFailed` is already on the merged pipeline port with
the right guard (`status IN ('QUEUED','PROCESSING')`, so a late DLQ message cannot overwrite a
terminal row).

### Behavior

```
loop while running:
  messages = sqsClient.receiveMessage(dlqUrl, waitTimeSeconds = 20, maxNumberOfMessages = 10,
                                      messageAttributeNames = ["All"])
  for each message:
      deliveryId = message attribute "delivery_id"          // primary, TASK-007-16
                   else parse the body's delivery_id        // fallback
      if (deliveryId present):
          pipelinePort.markFailed(deliveryId, "poison message: max receive count exceeded", now)
          log at error with delivery_id; increment the DLQ counter
      else:
          log the full message at error as an operational/security alert, correlate nothing
      sqsClient.deleteMessage(...)                          // always, both branches
```

- **Attribute first, body second** — ADR-004 §1's whole point is that correlation survives a body
  that fails to parse. Never parse the body before checking the attribute.
- **An uncorrelatable message is not a lost delivery.** ADR-004 §1 is explicit: it is a message
  this system did not produce (a misrouted publisher, a manual injection, a shared ARN). Log it in
  full, raise it as an operational/security alert, and deliberately do **not** guess at a row.
  This is the one place where logging a full message body is correct — it is not a delivery
  payload and there is nothing else to go on.
- **`markFailed` returning `false`** means the row already reached a terminal state. Normal, not
  an error: log at debug and still delete.
- **Every DLQ arrival is an alert** (ADR-002 §3: "any message landing in the SQS DLQ" pages the
  platform on-call). Emit a counter with no `client_id`/`subscription_id` tag and log at error.
- Same loop discipline as the main listener: gate on `challenge.worker.enabled`, catch `Throwable`
  per message and per cycle, one virtual thread per message, no `synchronized`, clean shutdown.
  Resolve the DLQ URL once at construction from `challenge.sqs.queues.deliveries-dlq`.
- **PII rule still applies to the correlated branch**: `delivery_id` and counters only, no
  `content`, no response body, no target URL.

## Out of Scope

- The main queue listener (TASK-007-17) and the use case.
- The redrive policy itself (TASK-007-04) — this consumer only reads what SQS moved.
- Redriving, replaying or recovering a `FAILED` row. ADR-004 §1's recovery action inserts a new
  row and is a later feature; this task never mutates a `FAILED` row afterwards.
- Alert routing, dashboards and Grafana configuration.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Mock `SqsClient` and `DeliveryPipelineRepositoryPort`, and drive one package-private
`pollOnce()` rather than the running loop.

Required unit scenarios:
- a message carrying the `delivery_id` attribute calls `markFailed` with that id, exactly once,
  and then deletes
- a message with **no** attribute but a parseable body falls back to the body's `delivery_id`
- **the headline scenario, named explicitly by the Tech Lead: a message whose body cannot be
  deserialized at all** (truncated JSON, or plain non-JSON bytes) **still correlates and its row
  is marked `FAILED`, using only the `delivery_id` message attribute.** This is the reason
  TASK-007-16 exists and the reason it lands first; assert that the body is never successfully
  parsed on this path and that `markFailed` is still called exactly once with the attribute's id.
- a message with neither yields no `markFailed` call, is logged at error, and is still deleted
- `markFailed` returning `false` does not throw and the message is still deleted
- a throwing `markFailed` does not escape `pollOnce`
- the receive request targets the DLQ URL with `waitTimeSeconds = 20` and `maxNumberOfMessages = 10`

The end-to-end scenarios — a poison message actually reaching `deliveries-dlq` after
`maxReceiveCount = 3`, and the row observably transitioning to `FAILED` in PostgreSQL — require
LocalStack and Testcontainers and are **explicitly deferred to the later integration phase**
(see `feature.md`). Do not build them here.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] Reads `deliveries-dlq` with long poll 20s and batch size 10.
- [ ] `delivery_id` is taken from the message attribute first, the body only as a fallback.
- [ ] A correlated message calls `markFailed` exactly once and is then deleted.
- [ ] A message with an **undeserializable body** but a present `delivery_id` attribute is still
      correlated and marked `FAILED` — covered by a named test.
- [ ] TASK-007-16 is merged in the working tree before these tests are written.
- [ ] An uncorrelatable message is logged in full as an alert, correlates to no row, and is deleted.
- [ ] Every path deletes the message; `ChangeMessageVisibility` appears nowhere.
- [ ] A `false` from `markFailed` is tolerated and never thrown.
- [ ] Every DLQ arrival increments an untagged counter and logs at error.
- [ ] Gated on `challenge.worker.enabled`; the loop survives any `Throwable`; clean shutdown.
- [ ] One virtual thread per message; no `synchronized`, no platform-thread pool.
- [ ] Every unit scenario listed above is covered.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- Added `DeliveryDlqConsumer` implementing `SmartLifecycle`: `start()` gates on
  `WorkerProperties.enabled()` and spawns one dedicated virtual thread running the poll loop;
  `stop()` flips a volatile flag and interrupts that thread. The loop catches `Throwable` per
  cycle and never exits on an exception.
- `pollOnce()` (package-private test seam) issues one `receiveMessage` against the DLQ URL
  (`waitTimeSeconds=20`, `maxNumberOfMessages=10`, `messageAttributeNames=All`, resolved once at
  construction via `GetQueueUrl` on `challenge.sqs.queues.deliveries-dlq`), then hands each
  message to a `try (ExecutorService = Executors.newVirtualThreadPerTaskExecutor())` block — one
  virtual thread per message, and `close()` blocks until every message in the batch is fully
  processed and deleted before `pollOnce()` returns, which is what makes the unit tests
  deterministic without a same-thread test seam.
- Correlation: `delivery_id` message attribute first; only when absent does it fall back to
  `objectMapper.readValue(body, NotificationEnvelope.class)` to pull `deliveryId` — a
  `RuntimeException` from that parse (tools.jackson throws unchecked) is swallowed and treated as
  "no delivery_id available," never re-thrown. This is what makes the headline scenario work: an
  undeserializable body with the attribute present never touches the body-parsing path's result at
  all, because the attribute check short-circuits it first.
- Correlated branch calls `pipelinePort.markFailed(deliveryId, "poison message: max receive count
  exceeded", Instant.now(clock))`; `true` logs at error, `false` (already-terminal row) logs at
  debug — both still delete. Uncorrelated branch logs the full `Message` at error as an
  operational/security alert and calls `markFailed` zero times. Every message increments an
  untagged `delivery.dlq.arrival` counter exactly once, regardless of branch. The whole per-message
  body is wrapped in `try/catch(Throwable)/finally { deleteMessage }`, so a throwing `markFailed`
  (or anything else) never escapes `pollOnce`, and `deleteMessage` runs on every path —
  `ChangeMessageVisibility` is never called.
- No `synchronized` anywhere; the loop and per-message dispatch both rely on virtual threads
  unmounting cleanly during blocking SQS/JDBC I/O.
- PII rule honored: log statements carry only `delivery_id` and the counter; the one full-message
  log is the explicitly-sanctioned uncorrelatable-message alert path, which carries no delivery
  content by construction (it is not a delivery payload).
- OWASP: no new surface. No SQL here (the port implementation owns that); no user-controlled data
  reaches a log format string beyond `delivery_id` (a UUID) and the alert-path full message
  (already sanctioned by the task). Nothing to flag to security-engineer.
- Verification: `./gradlew compileJava compileTestJava` passes clean, no warnings. Tests written
  but not run, per instruction, pending the Tech Lead's explicit run.
