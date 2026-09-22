---
id: TASK-009-01
feature: FEAT-009
title: Trace propagation across the SQS hop, named spans, relay precedence inversion and replay span link
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-009-01: Trace Propagation and Replay Linkage

## Feature

FEAT-009

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Sizing exception — read this first

This task deliberately exceeds the ~3-files/one-concern rule. **ADR-008's Consequences records it
as a one-off exception granted by the Tech Lead for FEAT-009 only.** It is not a precedent, and it
does not license further widening: everything outside the file list below is out of scope.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/tracing/MicrometerTraceContextAdapter.java` (modified)
  - ~~`src/main/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapter.java` (modified)~~
    — **corrected 2026-09-21: verified unchanged and correct as delivered.** It already writes
    `pointer.traceparent()` to the `traceparent` message attribute (`SqsNotificationQueueAdapter:108-113`)
    and `NotificationEnvelope.from` already carries the same value into the body's fourth field.
    Per ADR-008 §2.2 only the *value's provenance* changes, and that is fixed entirely by change 3
    in the use case. Listing this file as "modified" was a task-file error.
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryQueueListener.java` (modified)
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryDlqConsumer.java` (modified)
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/DeliveryRelayScheduler.java` (modified)
  - `src/main/java/com/cobre/challenge/adapter/in/web/ingest/EventIngestController.java` (modified)
  - `src/main/java/com/cobre/challenge/application/usecase/DispatchPendingDeliveriesUseCaseImpl.java` (modified — one expression)
  - `src/main/java/com/cobre/challenge/application/usecase/ReplayDeliveryUseCaseImpl.java` (modified — ~~one argument~~
    **corrected: one constructor dependency (`TraceContextPort`) plus two arguments.** See the
    post-hoc scope ruling below)
  - `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java` (modified — one line deleted)
  - the replay controller path for the `notification.replay` span (see §2.6 of the ADR) — concretely
    `adapter/in/web/selfservice/NotificationEventController.java` (modified) and
    `adapter/in/web/selfservice/ReplaySpanRecorder.java` (**new**)
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/Accepted.java` (modified —
    third component `Optional<String> originalTraceContext`). See the post-hoc scope ruling below
- Concern: **one** — trace context. Nothing else in this task.

**No unit tests are written for this task.** Explicit Tech Lead override, recorded 2026-09-21,
superseding the "plus unit tests for the above" line that stood here and every "a unit test
asserts…" clause in the Acceptance Criteria below. Existing tests are updated only as far as
constructor-signature changes force. No Testcontainers work.

## Post-hoc scope ruling (architect, 2026-09-21)

Four deviations were raised at review. All four are ruled on here; the file list and acceptance
criteria above and below are corrected accordingly, so the delivered diff is in scope as it stands
and nothing in this task needs rework.

### Blessed — necessary to satisfy §2.5, and no narrower mechanism exists

| Deviation | Ruling |
|---|---|
| `Accepted` gains `Optional<String> originalTraceContext` | **Blessed.** §2.5 requires the span link to be built from the **target row's** `trace_context`, and §2.6 requires the span to be created in the adapter. Only the use case loads the target row; only the adapter may hold a `Tracer`. Widening the `port/in` result is the sole mechanism that satisfies both without importing Micrometer into `application/usecase`. `Optional` (not null) per Effective Java Item 55; the record stays immutable (Item 17). |
| `ReplaySpanRecorder` is a new file | **Blessed.** Span creation in `adapter/in/web/selfservice` is exactly where §2.6 puts it. Keeping it out of the controller keeps the controller to orchestration only. Package-private and single-purpose (SRP). |
| `ReplayDeliveryUseCaseImpl` gains a `TraceContextPort` constructor dependency | **Blessed, and the "one argument" scope note was wrong.** This task's own change 5 requires the use case to pass "the replay request's current traceparent, from `TraceContextPort`". A constructor dependency on an existing `port/out` is the only hexagonal way to obtain it. The note underestimated its own requirement. |
| `SqsNotificationQueueAdapter` left unchanged | **Confirmed correct.** See the struck-through file-list entry above. |

**Recorded trade-off on `notification.replay`.** The Micrometer `Span.Builder` API accepts a link
only before `start()`, and the link's target is not known until the use case returns. The span is
therefore emitted *after* the replay completes and wraps no work — it is a marker carrying the
attribute and the link, not a duration. §2.6 requires it be "child of the HTTP server span, linked
per §2.5" and does not require it to bound the call, so this satisfies the ADR. It is written down
here because it is the first thing a reviewer will ask about.

**Recorded trade-off on the idempotency-replay path.** When `ReplayIdempotencyGuard` returns a
cached response without executing the lambda, no `notification.replay` span is emitted. That is
correct: no replay occurred on that request.

### Moved out of this task — not implementable within its scope

| Deviation | Ruling |
|---|---|
| `notification.dispatch` per-delivery span not implemented | **Not this task's, and correctly not attempted. Moved to TASK-009-04.** §2.6 puts the span in the relay adapter, but `DispatchPendingDeliveriesResult` returns only `(claimedCount, publishedCount)` — no per-delivery id or traceparent reaches `DeliveryRelayScheduler`, so there is nothing to parent a span to. Closing that gap means widening a `port/in` result type, changing the use case and adding a recorder to the relay adapter: four files and a second concern, which this task's scope note explicitly forbids ("modified — one expression"). `notification.relay.poll` was correctly delivered here. |
| `notification.attempt` missing `event_id`, `client_id`, `http.response.status_code` | **Never this task's; the acceptance criterion below was wrong. Moved to TASK-009-02.** All three are known only after `AttemptDeliveryUseCaseImpl` loads the row, and this task restricted that file to a one-line deletion. TASK-009-02 already owns that file and `DeliveryQueueListener`, and already owns putting `event_id`/`client_id` into MDC from the same load — the span tags are the same three identifiers through a second mechanism, so they belong in the same task and the same diff. The four attributes the listener *can* know from the pointer (`delivery_id`, `subscription_id`, attempt number, `outcome`) were correctly delivered here. |

## Ground truth — the defects this task fixes

Verified against commit `d117557`. Verify each of these still reads as stated **before** changing
it; if any does not, stop and report rather than guessing.

| # | Location | Current text / state |
|---|---|---|
| 1 | `AttemptDeliveryUseCaseImpl:181` | `command.traceparent().or(delivery::traceContext).ifPresent(traceId -> MDC.put(MDC_TRACE_ID, traceId));` |
| 2 | `DispatchPendingDeliveriesUseCaseImpl:67-72` | pointer built with `currentTraceparent.or(delivery::traceContext)` — precedence inverted |
| 3 | `ReplayDeliveryUseCaseImpl:58-71` | the replay `Delivery` is constructed with `Optional.empty()` in the `traceContext` position (the **last** constructor argument) |
| 4 | `MicrometerTraceContextAdapter` | hand-concatenates `"00-" + traceId + "-" + spanId + "-" + flags` |
| 5 | anywhere | no `Propagator`/`TextMapPropagator` `extract` call exists; no `Observation`, `@Observed`, `nextSpan` or `Tracer` outside `MicrometerTraceContextAdapter` |

## What to change

### 1. The propagator is the only formatter (ADR-008 §2.1)

`MicrometerTraceContextAdapter.currentTraceparent()` obtains its value by **injecting into a
single-entry carrier** with the configured Micrometer `Propagator` (W3C `traceparent`, the Boot
default) instead of concatenating fields. `TraceContextPort`'s signature **does not change** and
the port **does not** grow span-creation methods. Its existing swallow-and-return-empty contract
is kept.

### 2. Publish side (§2.2)

`SqsNotificationQueueAdapter` already sets a `traceparent` message attribute
(`TRACEPARENT_ATTRIBUTE`). **The attribute name, its lowercase spelling, and ADR-004 §1's
four-field JSON envelope are all unchanged.** Only the value's provenance changes, and that is
fixed by change 3. Nothing is added and nothing is removed from the envelope.

### 3. Relay precedence is inverted (§2.3) — the load-bearing change

```
pointer.traceparent = delivery.traceContext()                       // the row's own
                          .or(traceContextPort::currentTraceparent) // only when the row has none
```

This is the **exact reverse** of the current `DispatchPendingDeliveriesUseCaseImpl:67-72`. With
the current precedence one poll cycle's context is stamped onto every row in a batch of up to 500
(`challenge.relay.batch-limit`), merging unrelated business flows into one trace and discarding
each delivery's ingest trace.

The poll cycle gets its own separate root span `notification.relay.poll`, parent of nothing in the
business flow, so a poll with zero claims is still visible. **That span is this task's.**

~~`notification.dispatch` is created per delivery, as a child of that delivery's extracted context,
in the relay adapter (`DeliveryRelayScheduler`), not in the use case.~~ **Moved to TASK-009-04** —
it requires per-delivery data the use case's result type does not carry. See the post-hoc scope
ruling above.

### 4. Consume side (§2.4)

`DeliveryQueueListener` and `DeliveryDlqConsumer` extract the remote context from the message
attributes with the propagator and open `notification.attempt` / `notification.dlq` as a child of
it, running the use case inside that scope.

**Fallback order: message attribute first, `deliveries.trace_context` second.** When neither is
present, or either is malformed, start a **new root trace** and log nothing above `DEBUG` about
it. An unparseable traceparent must **never** fail a delivery (A10).

**Delete `AttemptDeliveryUseCaseImpl:181.`** `AttemptDeliveryCommand.traceparent()` **stays on the
command** — the use case still needs the value for the fallback decision when it loads the row —
but the use case no longer interprets it as a trace id and no longer puts it in MDC.

### 5. Replay (§2.5)

| Field | Value on the replay row |
|---|---|
| `deliveries.trace_context` | the **replay request's** current traceparent, from `TraceContextPort` — replacing the `Optional.empty()` at `ReplayDeliveryUseCaseImpl:58-71` |
| `deliveries.replaced_delivery_id` | unchanged: the original delivery id (ADR-003 §3) |
| span link on `notification.replay` | the original delivery's trace, reconstructed from the target row's `trace_context`, attached as an OpenTelemetry span link with attribute `notification.replay_of_delivery_id` |

The link is **best-effort**: an original row whose `trace_context` is null produces a replay span
with no link and the attribute only. It fails silently by design rather than failing the replay.

### 6. The five named spans (§2.6)

| Span | Created in |
|---|---|
| `notification.ingest` | `adapter/in/web/ingest` — child of the auto-instrumented HTTP server span, around the use-case call |
| ~~`notification.dispatch`~~ | ~~relay adapter, per delivery~~ — **TASK-009-04**, see the post-hoc ruling |
| `notification.relay.poll` | relay adapter, per poll cycle, root of its own trace |
| `notification.attempt` | `DeliveryQueueListener`, child of the extracted context |
| `notification.dlq` | `DeliveryDlqConsumer`, child of the extracted context |
| `notification.replay` | replay endpoint path, child of the HTTP server span, linked per §2.5 |

`notification.attempt` attributes, **split by what the listener can know**:

| Attribute | Task |
|---|---|
| `delivery_id`, `subscription_id`, attempt number, `outcome` | **this task** — all four are on the pointer command or the returned result |
| `event_id`, `client_id`, `http.response.status_code` | **TASK-009-02** — known only after the use case loads the row |

**Never** the target URL, the request body or the response body (§3.3).

**Span creation lives in adapters, not in use cases** (ADR-002 Amendment C3, restated in §2.6).

### 7. Virtual threads

Every scope opened here is opened and closed on the **same virtual thread**, one per message or
request, **in a `finally`**. Nothing is `synchronized`; nothing holds a scope across a blocking
call it did not itself initiate. A leaked scope corrupts subsequent spans on that carrier thread
and is the one real hazard of this design — which is why the `finally` is an acceptance criterion
below, not a style preference.

## Out of Scope

- **Any MDC change** beyond deleting line 181 — `event_id`, `client_id`, the ownership move and
  the `MDC.clear()` relocation are TASK-009-02.
- **Any meter, timer or gauge.** TASK-009-02 (timers) and TASK-009-03 (gauge, filter).
- **Any `application*.yaml` property**, log appender wiring or `logback-spring.xml` — TASK-009-03.
- **Any health or actuator configuration** — TASK-009-03.
- **The `delivery.dlq.arrival` rename** — TASK-009-03, even though this task edits the same file.
- Any migration. `deliveries.trace_context` already exists (`V2`).
- Any change to `TraceContextPort`'s signature, to `AttemptDeliveryCommand`, to the SQS message
  attribute name, or to ADR-004 §1's envelope.
- Any security configuration.

## Acceptance Criteria

- [ ] `AttemptDeliveryUseCaseImpl:181` — the `MDC.put(MDC_TRACE_ID, ...)` of the whole traceparent
      — is **deleted**. No code anywhere hand-sets `trace_id` or `span_id` in MDC.
- [ ] `AttemptDeliveryCommand.traceparent()` still exists and is still populated; only its
      interpretation changed.
- [x] `DispatchPendingDeliveriesUseCaseImpl:67-72` reads `delivery.traceContext().or(...)`,
      the reverse of its current order. ~~A unit test asserts…~~ (no unit tests, per the Tech Lead
      override recorded in Scope).
- [ ] ~~A unit test asserts that a batch of N rows with N distinct `trace_context` values produces N
      pointers with N distinct traceparents.~~ **Withdrawn** — no unit tests for this task.
- [ ] `ReplayDeliveryUseCaseImpl` no longer passes `Optional.empty()` for `traceContext`; it passes
      the replay request's current traceparent from `TraceContextPort`.
- [ ] `MicrometerTraceContextAdapter` contains **no string concatenation of a traceparent**; the
      value comes from a `Propagator` inject into a single-entry carrier.
- [ ] `TraceContextPort` is unchanged: still one method, no span-creation method added.
- [ ] A `Propagator` `extract` is called in `DeliveryQueueListener` and in `DeliveryDlqConsumer`,
      with fallback order message-attribute-then-body-envelope. **Corrected 2026-09-21:** the
      criterion previously read "message-attribute-then-`deliveries.trace_context`". The listener
      holds no repository port and must not acquire one — a database read per message purely to
      parent a span is a cost and a failure mode the A10 rule forbids. The
      `deliveries.trace_context` fallback is satisfied **transitively at publish time**: change 3
      makes the pointer's traceparent the row's own `trace_context`, and
      `SqsNotificationQueueAdapter` writes that into both the message attribute and the envelope
      body. Both carriers therefore already carry the column's value.
- [ ] An absent, empty or malformed traceparent starts a new root trace, logs at most at `DEBUG`,
      and **does not fail the delivery** (A10). ~~A unit test covers a malformed value explicitly.~~
- [ ] The five named spans in scope for this task (`notification.ingest`, `notification.relay.poll`,
      `notification.attempt`, `notification.dlq`, `notification.replay`) are created in the adapters
      listed, **never** in a use case. `notification.dispatch` is TASK-009-04.
- [ ] `notification.relay.poll` is a separate root span emitted even when the poll claims zero rows.
      ~~`notification.dispatch` is created per delivery~~ — TASK-009-04.
- [ ] `notification.attempt` carries `delivery_id`, `subscription_id`, attempt number and `outcome`,
      and carries **neither** the target URL, **nor** the request body, **nor** the response body.
      Its remaining three attributes (`event_id`, `client_id`, `http.response.status_code`) are
      TASK-009-02.
- [ ] `notification.replay` carries a span link to the original trace when the target row has a
      `trace_context`, and carries `notification.replay_of_delivery_id` in both cases; a null
      original `trace_context` produces no link and **does not** fail the replay.
- [ ] **Every span scope and every context scope opened is closed in a `finally` on the same
      virtual thread that opened it.** No `synchronized` block is introduced anywhere.
- [ ] The SQS `traceparent` message attribute name is unchanged and ADR-004 §1's four-field
      envelope is unchanged.
- [ ] **Cardinality rule** (ADR-008 §4.3, restated here because this task may add span attributes):
      `client_id` and `subscription_id` are never a tag on any counter, gauge, timer or
      distribution summary — they are trace and log dimensions only. This task adds **no meter at
      all**; if that changes, it is out of scope.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A09 — no payload reaches a span attribute; A10 — no
      observability failure fails a write).

## Verification for this task

`./gradlew compileJava compileTestJava`. **No unit tests are written for this task** (Tech Lead
override). **Do not run `./gradlew test` or `./gradlew build`** — FEAT-009 runs a single full build
at the end, in TASK-009-04 (moved from TASK-009-03 when TASK-009-04 was added).

## Definition of Done

Code written and compiling. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
