---
id: TASK-006-07
feature: FEAT-006
title: DispatchPendingDeliveriesUseCaseImpl — commit, then publish, then count
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-03, TASK-006-06]
date: 2026-09-21
---

# TASK-006-07: `DispatchPendingDeliveriesUseCaseImpl`

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/DispatchPendingDeliveriesUseCaseImpl.java` (new)
- Concern: one relay cycle — claim inside a transaction, publish after it commits, report what
  happened.

### The flow

```
dispatch(command):
  1. claimed = relayBatchClaimer.claimAndPromote(command.batchLimit(), command.asOf())   // TX commits here
  2. if claimed.isEmpty() -> return new DispatchPendingDeliveriesResult(0, 0)
  3. pointers = claimed.map(toPointer)
  4. result = queuePort.publishBatch(pointers)                                            // no TX
  5. count, log, return new DispatchPendingDeliveriesResult(claimed.size(), result.publishedCount())
```

**No `@Transactional` on this class.** The transaction is `RelayBatchClaimer`'s and it must be
closed before step 4 runs. ADR-002 §2.1 is explicit about the ordering — claim, mark `QUEUED`,
push the clock, **commit**, *then* `SendMessageBatch` — and ADR-001 §1 makes the reason general:
nothing is ever enqueued before it is committed, and no network call is ever made inside a
database transaction.

### Mapping a claimed `Delivery` to a `DeliveryPointer`

```
new DeliveryPointer(
    delivery.deliveryId(),
    delivery.subscriptionId(),
    delivery.attemptCount(),                                        // attemptHint
    traceContextPort.currentTraceparent().or(delivery::traceContext))
```

The relay injects the **current** dispatch span's traceparent so `notification.ingest ->
notification.dispatch -> notification.attempt` reads as one trace (ADR-002 §3.1). It falls back to
the row's persisted `trace_context` when no span is active — the case §3.1 names explicitly, a
sweeper re-publish long after the original span ended. `TraceContextPort` and its Micrometer
adapter already exist (ADR-002 Amendment C3); inject the port, never Micrometer's tracer directly.

### Failure handling — three distinct paths, three distinct behaviours

1. **The claim throws.** Let it propagate. The transaction rolled back, nothing was marked
   `QUEUED`, nothing was enqueued: the cycle is a no-op and the rows stay exactly as due as they
   were. Fail-closed (OWASP A10). The scheduler (TASK-006-09) is what keeps one bad cycle from
   killing the schedule.
2. **`publishBatch` returns failed entries.** Do nothing to the rows. They are `QUEUED` with
   `next_attempt_at` already pushed 5 minutes forward, and that pushed clock **is** the recovery
   mechanism (ADR-002 §2.1). **No compensating update, no status rollback, no in-cycle retry, no
   `deferDelivery` call.** Count them and log at warn by `delivery_id` only.
3. **`publishBatch` throws** (whole-request failure — credentials, network, queue gone). Catch
   `Throwable` at this call site, count it, log at warn, and return
   `new DispatchPendingDeliveriesResult(claimed.size(), 0)` rather than propagating. The claim is
   already committed and the rows are already recoverable by the same pushed clock, so turning a
   publish outage into a thrown cycle would add nothing and would lose the claimed count. This is
   the same asymmetry ADR-001 §1 states: a message lost in SQS is recoverable; a row lost in
   PostgreSQL is not.

### Metrics (ADR-002 §3)

Micrometer counters, incremented here. **No `client_id` and no `subscription_id` tag on any of
them** — ADR-002 Q8 and §3.1 forbid it outright, and a `MeterFilter` that strips a tag afterwards
still pays the cardinality cost before it runs.

| Meter | What it counts |
|---|---|
| `notification.relay.claimed` | rows returned by the claim |
| `notification.relay.published` | entries SQS accepted |
| `notification.relay.publish.failed` | entries SQS refused, plus every entry of a thrown batch |
| `notification.circuit.transition` tagged `direction="OPEN_TO_HALF_OPEN"` | promotions this cycle (ADR-002 §3's circuit-transition counter by direction) |

The promotion count comes from `RelayBatchClaimer` (TASK-006-06 left the shape of that to you —
either a second small method or a count returned alongside; pick the smaller change).

### Logging (ADR-002 §3.1)

Structured, `delivery_id` / `subscription_id` / claimed count / published count / trace id only.
**Never** `content`, never a target URL, never a signature header, never a response body. An idle
cycle (zero claimed) must not log at info — the relay runs every 5 seconds.

### Concurrency

No `synchronized`, no `ThreadLocal` held across the publish, no executor of its own. The cycle
runs on whatever thread the scheduler gives it, which is a virtual thread
(`spring.threads.virtual.enabled=true`).

## Out of Scope

- The due-query SQL, `RelayBatchClaimer`'s internals, and the SQS adapter.
- The scheduler and its configuration — TASK-006-08 and TASK-006-09.
- `DispatchPendingDeliveriesCommand` and `DispatchPendingDeliveriesResult`. Both are sufficient as
  merged; do not add fields.
- Any `deliveries` write on the publish-failure path. This is the single most likely way to get
  this feature wrong — re-read ADR-002 §2.1's `SendMessageBatch` bullet before adding one.
- The worker, the DLQ, and every circuit transition other than the promotion counter.

## Acceptance Criteria

- [ ] Implements `DispatchPendingDeliveriesUseCase` with no signature change.
- [ ] **No `@Transactional`** anywhere in this class; the claim is delegated to
      `RelayBatchClaimer`.
- [ ] Every publish happens strictly after the claim transaction has committed.
- [ ] An empty claim returns `(0, 0)` and makes no SQS call.
- [ ] Failed entries trigger **no** database write of any kind.
- [ ] A thrown `publishBatch` is caught and reported, not propagated; a thrown claim propagates.
- [ ] Traceparent is taken from `TraceContextPort` with a fallback to the row's `trace_context`.
- [ ] The four counters exist with the names above and carry no `client_id`/`subscription_id` tag.
- [ ] No payload, URL, or secret appears in any log statement.
- [ ] No `synchronized`, no `ThreadLocal`, no mutable field.
- [ ] `./gradlew build` compiles and existing tests pass.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
