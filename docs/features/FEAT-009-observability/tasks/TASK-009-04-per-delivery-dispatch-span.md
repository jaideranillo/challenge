---
id: TASK-009-04
feature: FEAT-009
title: The per-delivery notification.dispatch span, and the feature's single full build
status: Not Started
agent: backend-engineer
depends_on: [TASK-009-01, TASK-009-02, TASK-009-03]
date: 2026-09-21
---

# TASK-009-04: The Per-Delivery `notification.dispatch` Span

## Feature

FEAT-009

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Why this task exists

ADR-008 §2.3 and §2.6 require `notification.dispatch` to be created **per delivery**, as a child of
that delivery's own extracted context, **in the relay adapter**. TASK-009-01 was scoped to change
`DispatchPendingDeliveriesUseCaseImpl` by "one expression" (the precedence inversion) and delivered
`notification.relay.poll` correctly, but could not deliver `notification.dispatch`: nothing in
`DispatchPendingDeliveriesResult` — `(int claimedCount, int publishedCount)` — tells
`DeliveryRelayScheduler` **which** deliveries were dispatched or **what context** each carries, so
there is nothing to parent a span to.

Closing that gap is four files and a second concern, which TASK-009-01's scope note forbade. It is
this task, ruled at that task's review on 2026-09-21. **This is the last open item of ADR-008 §2.**

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/DispatchPendingDeliveriesResult.java` (modified — carries per-delivery data out)
  - `src/main/java/com/cobre/challenge/application/usecase/DispatchPendingDeliveriesUseCaseImpl.java` (modified — populates it)
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/DispatchSpanRecorder.java` (**new** — creates the spans)
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/DeliveryRelayScheduler.java` (modified — calls the recorder)
  - plus unit tests for the above
- Concern: **one** — the per-delivery dispatch span.

## What to change

### 1. The result type carries per-delivery data out

`DispatchPendingDeliveriesResult` grows a list of per-delivery entries alongside its two counts.
One entry per **claimed** delivery, each carrying:

| Field | Why |
|---|---|
| the delivery id | the span's `delivery_id` attribute, and the reviewer's join key |
| the delivery's traceparent | the span's parent, extracted by the propagator. This is the value the pointer carried, i.e. `delivery.traceContext().or(currentTraceparent)` after TASK-009-01's inversion. `Optional`, because a row with no `trace_context` and no active scheduler span has none |
| whether the publish succeeded | so the span can carry the dispatch outcome rather than asserting success for a row that failed to publish |

Constraints on the shape:

- A `record` per entry, in the same `port/in/.../dto` package, immutable (Effective Java Item 17).
- `Optional<String>` for the traceparent, never null, never `""` as a sentinel (Items 54-55).
- The list is **unmodifiable and never null** — an empty list for a zero-claim cycle, never null.
- The counts stay. They are what the log line and any future meter read; do not derive them at the
  call site from the list's size.
- **Bounded by `challenge.relay.batch-limit` (500).** This is a per-cycle list that dies with the
  cycle, not an accumulating buffer.

**This is a `port/in` contract change.** It is the same widen-the-result-so-the-adapter-can-observe
pattern TASK-009-01 used for `Accepted`/`ReplaySpanRecorder` and TASK-009-02 used for
`AttemptDeliveryResult`, and it is the only mechanism that satisfies §2.6's "created in the relay
adapter" without putting a `Tracer` into `application/usecase` (ADR-002 Amendment C3).

### 2. `DispatchSpanRecorder` creates the spans

A new package-private `@Component` in `adapter/in/scheduling`, modelled on
`ReplaySpanRecorder`. For each entry:

- Extract the entry's traceparent with the `Propagator` into a single-entry carrier, exactly as
  `DeliveryQueueListener.startAttemptSpan` does.
- Open `notification.dispatch` as a child of it, tag `delivery_id` and the publish outcome, end it.
- An absent or malformed traceparent yields a **new root span**, logged at most at `DEBUG`.
- **Never throws.** A failure recording a span must not fail, delay or skip a relay cycle (A10).

### 3. The scheduler calls it

`DeliveryRelayScheduler.pollOnce` passes the result's entries to the recorder **after**
`dispatch(...)` returns, inside the existing `notification.relay.poll` scope's `try`, before its
`finally`.

**Recorded trade-off, and the reason this is acceptable rather than a compromise.** The span is
emitted after the publish rather than around it, so it is a marker with near-zero duration, not a
measurement. That is correct for this design: §2.2 makes the SQS message attribute carry the
**row's** traceparent, not the dispatch span's, so `notification.attempt` parents to the ingest
context either way. `notification.dispatch` is a leaf marking "the relay moved this row", exactly
the shape §2.3 describes ("one poll span plus N per-delivery spans in N different traces"). Relay
*timing* is `notification.relay.dispatch.latency`, registered by TASK-009-02.

### 4. Virtual threads

The recorder runs on the scheduler's own thread, inline, after the use-case call. Every span it
opens is ended before the loop's next iteration; nothing is `synchronized`; no scope is held across
a blocking call. `notification.relay.poll`'s scope is still open around all of it and is still
closed in the same `finally` on the same thread.

## Out of Scope

- **Any change to what the SQS message carries.** The attribute name, its value's provenance and
  ADR-004 §1's four-field envelope are all unchanged. The dispatch span is not injected into the
  message — doing so would reparent `notification.attempt` onto the relay instead of ingest and
  would undo TASK-009-01's precedence inversion.
- **Any change to the precedence inversion itself** — delivered in TASK-009-01, do not touch it.
- **Any meter, timer or gauge**, including `notification.relay.dispatch.latency` (TASK-009-02) and
  the DLQ depth gauge (TASK-009-03).
- **Any MDC change** — TASK-009-02.
- **Any `application*.yaml` property, appender, health or actuator configuration** — TASK-009-03.
- Any migration, any security configuration, any change to `TraceContextPort`.

## Acceptance Criteria

- [ ] `notification.dispatch` is created **once per claimed delivery**, in
      `adapter/in/scheduling`, **never** in a use case. No `io.micrometer.tracing` import appears
      in `application/usecase`.
- [ ] Each span's parent is that delivery's own extracted context, so a batch of N rows with N
      distinct `trace_context` values produces N spans in N distinct traces. A unit test asserts
      this against a batch of at least three rows with distinct values.
- [ ] A row with no traceparent, or a malformed one, produces a **new root span**, logs at most at
      `DEBUG`, and **does not fail or shorten the relay cycle**. A unit test covers a malformed
      value explicitly (A10).
- [ ] The recorder never throws: a unit test asserts a cycle completes and
      `notification.relay.poll` still ends when span recording fails.
- [ ] `notification.relay.poll` is unchanged: still a separate root span, still parent of nothing in
      the business flow, still emitted on a zero-claim cycle.
- [ ] `DispatchPendingDeliveriesResult`'s per-delivery list is unmodifiable, never null, empty on a
      zero-claim cycle, and its entries use `Optional` rather than null or a sentinel. The two
      existing counts are unchanged in name and meaning.
- [ ] The SQS `traceparent` message attribute name, its value and ADR-004 §1's envelope are
      **unchanged**; `notification.attempt` still parents to the ingest context, not to the
      dispatch span.
- [ ] Every span opened is ended, and every scope closed in a `finally` on the same virtual thread
      that opened it. No `synchronized` block is introduced anywhere.
- [ ] **Cardinality rule** (ADR-008 §4.3): `client_id` and `subscription_id` are never a tag on any
      counter, gauge, timer or distribution summary, and neither are `delivery_id` or `event_id`.
      They are trace and log dimensions only. **This task adds no meter at all**; if that changes,
      it is out of scope. TASK-009-03's build-failing test will catch a violation.
- [ ] No payload reaches a span attribute: no target URL, no request body, no response body (§3.3).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A09 — no payload on a span; A10 — no observability failure
      fails or skips a relay cycle).

## Verification for this task

`./gradlew compileJava compileTestJava` plus the unit tests this task writes, **then the single
full `./gradlew build` for the whole feature.** This task is now the last of FEAT-009, so the build
pass that TASK-009-03 previously owned moves here. A failure in it belongs to whichever task
introduced it, not automatically to this one.

## Definition of Done

Code written, unit tests passing, `./gradlew build` green for the feature. **Do not run `git add`
or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the
working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
