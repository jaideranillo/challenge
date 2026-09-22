---
id: TASK-009-02
feature: FEAT-009
title: MDC ownership and the missing keys, payload redaction, exception-message sanitization, and the two latency timers
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-009-01]
date: 2026-09-21
---

# TASK-009-02: Log Context, Redaction and Latency Meters

## Feature

FEAT-009

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Sizing exception — read this first

This task deliberately exceeds the ~3-files/one-concern rule, per the one-off exception recorded
in ADR-008's Consequences and granted by the Tech Lead for FEAT-009 only. Not a precedent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryQueueListener.java` (modified — takes MDC scope ownership)
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryDlqConsumer.java` (modified — same)
  - `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java` (modified — adds `event_id`/`client_id` to MDC, populates the widened result, loses `MDC.clear()`)
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/AttemptDeliveryResult.java` (modified — carries `event_id`, `client_id` and the HTTP status code out to the listener; see §5 below)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/JdkWebhookClientAdapter.java` (modified — the attempt-latency timer)
  - `src/main/java/com/cobre/challenge/application/usecase/DispatchPendingDeliveriesUseCaseImpl.java` (modified — the relay-dispatch-latency timer) or the relay adapter, whichever bounds one poll cycle correctly
  - the `toString()` of every type holding `content` or `response_excerpt` (`NotificationEvent`, the attempt/outcome records, and the inbound/outbound DTOs that carry them)
  - plus unit tests for the above
- Concern: what a log record and a latency measurement contain.

## Ground truth — the defects this task fixes

Verified against commit `d117557`.

| # | Location | Current state |
|---|---|---|
| 6 | `AttemptDeliveryUseCaseImpl` | sets `delivery_id`, `subscription_id`, `attempt_number`, `status_class` and (until TASK-009-01) `trace_id`, and calls **`MDC.clear()` in its `finally`** — clearing keys it does not own. **`event_id` and `client_id` are never set anywhere in the codebase**, though ADR-002 §3 requires both |
| 7 | anywhere | **No `Timer`, no `DistributionSummary`, no `Gauge` of any kind exists.** ADR-002 §3's "attempt latency (timer)" and the monitoring surface's "p99 attempt latency" have nothing behind them, so there is nothing for a percentile histogram to be enabled *on* |

## What to change

### 1. MDC keys and their ownership (ADR-008 §3.2)

| Key | Set by | Note |
|---|---|---|
| `delivery_id` | adapter/in, at the boundary | on the ingest, dispatch, attempt, DLQ and replay paths |
| `event_id` | the use case, as soon as the **`Delivery` row** loads | **currently never set anywhere.** "The row" is the delivery row, which denormalizes both — **not** the later `NotificationEvent` load. Set them at the earliest point they are known, so the subscription-not-found and event-not-found short-circuits carry real identifiers (clarified 2026-09-21 at review) |
| `client_id` | the use case, as soon as the **`Delivery` row** loads | **currently never set anywhere.** This is the field ADR-002 §3 requires. Same load as `event_id` |
| `subscription_id` | adapter/in where known, use case otherwise | already set today on the attempt path |
| `attempt_number`, `status_class` | the use case | already set today. Kept |
| `trace_id`, `span_id` | **nobody** | attached to the record by the appender from the live OTel context. **Hand-setting either is forbidden** |

**Ownership rule: the outermost adapter opens the MDC scope and is the only thing that closes it.**
A use case may `put` additional keys inside that scope and must **never** call `MDC.clear()` or
`MDC.remove()` on a key it did not set. `AttemptDeliveryUseCaseImpl`'s current `MDC.clear()` in its
`finally` violates this the moment the listener sets anything, so **it moves to the listener's
`finally`**, alongside the span scope TASK-009-01 put there. One `finally`, two resources, same
lifetime.

The clear must be **unconditional**: MDC is a `ThreadLocal`, a virtual thread's carrier is reused,
and a thread-local surviving a request would attribute one delivery's id to another's log line.
With one virtual thread per message or request and an adapter-level `finally`, this is correct and
carries **no pinning risk**.

`org.slf4j.MDC` in the application layer is permitted, as `org.slf4j.Logger` already is. The
hexagonal line here is about **lifecycle**, not about the import.

### 2. Redaction (§3.3)

| Field | In logs |
|---|---|
| `notification_events.content` | **omitted entirely.** Not truncated, not hashed |
| `delivery_attempts.response_excerpt` | **omitted entirely.** The 1024-character `challenge.worker.response-excerpt-limit` truncation is a **database-column bound, not a licence to log the truncated value** — a 1024-character prefix of a client's response body is still the client's payload |
| substituted diagnostics | `content_length` and `response_body_length` as integers, plus the HTTP status code and the classified outcome |

**Correction, 2026-09-21, architect error in this table — not an implementer deviation.** "all
already available" was wrong for `response_body_length`. The only length crossing
`WebhookClientPort` is the **already-truncated** `WebhookResponse.responseExcerpt()`, so the value
logged by this task saturates at `challenge.worker.response-excerpt-limit` (1024) and cannot answer
ADR-008 §3.3's own third question, "was it enormous". Accepted as delivered for this task; the
untruncated length is carried across the port by **TASK-009-06**. Units are **characters**, not
bytes as ADR-008 §3.3 words it — see TASK-009-06 for why, and it reopens nothing in the ADR.

**Correlation identifiers are explicitly exempt and are logged in full.** `event_id`,
`delivery_id`, `client_id`, `subscription_id`, `event_type`, `trace_id` and `span_id` are logged
**unredacted, untruncated and unhashed**, on every line that has them. Redacting or masking any of
them would destroy the correlation this feature exists to create. **The redaction rule applies to
`content` and `response_excerpt` only, and to nothing else. If a field is an identifier, it is
logged in full.**

**No hashing.** A hash of a low-entropy payload is reversible by enumeration, so it carries the
disclosure risk of the plaintext with none of its diagnostic value.

**Enforcement, three layers:**

1. **`toString()` redaction** on every type holding either field — prints a redaction marker and
   the length in place of the value. This is the layer that catches the realistic accident, which
   is not `log.info(content)` but **`log.info("processing {}", event)`**.
2. **Unit tests** asserting each such `toString()` contains neither the value nor any prefix of it.
3. **The review rule**: no logger call anywhere takes `content` or `response_excerpt`, or any
   substring of either, as an argument, **at any level including `DEBUG` and `TRACE`**. A `DEBUG`
   line is still a line in Loki.

### 3. Exception and error logging (§3.4)

**Exceptions are logged in full by default** — class, message and the complete stack trace — and
are **not** subject to the omission rule above. Truncating stack traces to satisfy a redaction rule
would remove the single most useful diagnostic this system produces.

**The one carve-out: an exception message that embeds raw input or a raw response body must have
the embedded content stripped before the exception is logged.** The stack-trace path is otherwise
a direct bypass around the redaction rule: a parser can put the very bytes §3.3 forbids into a
message that §3.4 then logs verbatim. Concretely,
`JsonParseException: Unexpected token at "...raw content..."` writes client payload into Loki no
less than `log.info(content)` does, and is **harder to notice in review** because the logging call
itself looks innocuous.

Types to check for, at minimum:

| Category | Examples in scope |
|---|---|
| JSON parse errors | Jackson `JsonParseException`, `JsonMappingException` and subtypes — they quote the offending input and report its location |
| Deserialization / binding errors | `HttpMessageNotReadableException` (which wraps and re-reports the Jackson message), any `MismatchedInputException` variant |
| Anything echoing its input | validation and conversion failures including the rejected value, HTTP client exceptions including the response body, `IllegalArgumentException`s constructed with the offending value interpolated |

**Rule: at every exception-handling site introduced or touched by this feature, determine whether
the exception type can echo request content or response body in its message; if it can, log a
sanitized message** (exception class, error location/offset, length of the offending input)
**rather than `ex.getMessage()` verbatim.** The stack trace is logged unchanged. Where application
code constructs a wrapping exception, its message is built from **metadata only**, never by
interpolating the raw value.

This is a **per-site judgement, not a global filter**, with no automated control behind it. Its
silent failure mode: nothing breaks, the payload simply appears in the log store.

### 4. The two new latency timers (§4.1)

| Meter | Type | Tags | Semantics |
|---|---|---|---|
| `notification.delivery.attempt.latency` | timer | `outcome`, `status_class` | the outbound webhook POST, measured **around the HTTP call in the webhook client adapter**, covering connect plus read. This is ADR-002 §3's "attempt latency (timer)" |
| `notification.relay.dispatch.latency` | timer | none | one poll cycle, from the claim query to the last publish returning. Its p99 against `challenge.relay.poll-interval` (5s) is what says whether the relay is keeping up |

**No bespoke ingest timer is added.** `http.server.requests` is auto-instrumented, already present,
and its `uri` tag already separates `/internal/events` from the self-service endpoints. A second
meter measuring the same wall clock as an existing one is duplication — and the Boot meter keeps
`client_id` off it by construction.

**The histogram and SLO-bucket configuration for these timers is TASK-009-03, not this task.**
Register the timers; do not configure their distribution here.

### 5. The three `notification.attempt` span attributes moved here from TASK-009-01

**Added 2026-09-21 by architect ruling on TASK-009-01's review.** ADR-008 §2.6 requires
`notification.attempt` to carry seven attributes. TASK-009-01 delivered the four the listener can
know from the pointer (`delivery_id`, `subscription_id`, attempt number, `outcome`). The remaining
three are known only after the use case loads the row, which is this task's file and this task's
load:

| Attribute | Source |
|---|---|
| `event_id` | `Delivery.eventId()`, the same load that feeds the MDC key in §1 |
| `client_id` | `Delivery.clientId()`, same |
| `http.response.status_code` | the webhook response's status code; absent when no HTTP attempt was made (lost claim, bulkhead or circuit deferral, pre-HTTP egress rejection) |

**Mechanism — and it is not "the use case tags the span".** Span creation and span mutation both
require a `Tracer`, and ADR-002 Amendment C3 plus ADR-008 §2.6 keep Micrometer out of
`application/usecase`. Instead, widen `AttemptDeliveryResult` with these three values and have
`DeliveryQueueListener` tag the span from the returned result, **before the `span.end()` already in
its `finally`**. This is the same widen-the-result-then-tag-in-the-adapter pattern TASK-009-01 used
for `Accepted`/`ReplaySpanRecorder`, and it is the only one that satisfies both §2.6 and the
hexagonal rule.

Shape: absent values are `Optional`/`OptionalInt`, never null and never a sentinel (Effective Java
Items 54-55); the record stays immutable (Item 17). Prefer one nested record over three loose
components if that reads better — either is acceptable, a mutable holder is not.

**Also correct the attempt-number tag while here.** TASK-009-01 tags `attempt_number` from
`command.attemptHint()`, which is the pointer's hint, not the number the use case actually
attempted (`attemptCount + 1`). Carry the real number out on the widened result and tag that,
falling back to the hint when no attempt was made.

**Known gap, accepted, not a defect to fix:** when the use case **throws**, no result exists and
these attributes are absent from the span. The span still carries `span.error(t)` and the four
pointer-derived attributes. Reading them off a partially-failed call would require the use case to
publish state mid-flight, which is worse.

**Cardinality:** these are **span** attributes. `client_id` on a span is required by §2.6;
`client_id` on a **meter** is forbidden by §4.3. Both timers this task registers must carry
neither.

## Out of Scope

- **Any trace, span or propagator change beyond the three `notification.attempt` attributes and the
  attempt-number correction in §5 above** — everything else is TASK-009-01, which has landed. Do
  not create a new span, do not touch a `Propagator`, do not touch `notification.dispatch` (that is
  TASK-009-04).
- **Any `application*.yaml` property**, including the histogram and SLO buckets for the two timers
  this task registers, the OTLP log export properties, and anything under `management.*` —
  TASK-009-03.
- **The DLQ depth gauge, the `MeterFilter`, its build-failing test, the `delivery.dlq.arrival`
  rename, and the health probe groups** — all TASK-009-03.
- Any `logback-spring.xml` or appender wiring — TASK-009-03.
- Any migration, any port signature, any security configuration.
- Changing `challenge.worker.response-excerpt-limit` or the `response_excerpt` **column** — the
  database keeps storing it; only logging it is forbidden.

## Acceptance Criteria

- [ ] `event_id` and `client_id` are in MDC on the attempt path — both were previously set
      **nowhere in the codebase**. A unit test asserts both are present during the use-case call.
- [ ] `AttemptDeliveryUseCaseImpl` no longer calls `MDC.clear()`; the unconditional clear lives in
      the listener's `finally`, in the same `finally` as TASK-009-01's span scope.
- [ ] `DeliveryQueueListener` and `DeliveryDlqConsumer` each open the MDC scope at the boundary and
      close it unconditionally in a `finally`, on the same virtual thread. A unit test asserts MDC
      is empty after a call that **threw**.
- [ ] No code anywhere hand-sets `trace_id` or `span_id` in MDC (re-asserted after TASK-009-01).
- [ ] `notification.attempt` carries `event_id`, `client_id` and `http.response.status_code` (§5),
      tagged by `DeliveryQueueListener` from a widened `AttemptDeliveryResult` before the existing
      `span.end()`. **No `io.micrometer.tracing` import appears in `application/usecase`.**
- [ ] `notification.attempt`'s attempt-number tag is the number actually attempted, not the
      pointer's `attemptHint`, whenever an attempt was made.
- [ ] The widened `AttemptDeliveryResult` uses `Optional`/`OptionalInt` for absent values, never
      null and never a sentinel, and stays immutable.
- [ ] `notification.attempt` still carries **neither** the target URL, **nor** the request body,
      **nor** the response body (§3.3), and `content`/`response_excerpt` reach no span attribute.
- [ ] Every type holding `content` or `response_excerpt` overrides `toString()` to print a
      redaction marker plus the length, and a unit test asserts each such `toString()` contains
      **neither the value nor any prefix of it**.
- [ ] No logger call anywhere takes `content` or `response_excerpt` or any substring as an
      argument, at **any** level including `DEBUG` and `TRACE`.
- [ ] `content_length` and `response_body_length` are logged as integers alongside the status code
      and the classified outcome.
- [ ] **Identifiers are logged in full**: a test asserts `event_id`, `delivery_id`, `client_id`,
      `subscription_id` appear unredacted, untruncated and unhashed. Redacting an identifier is a
      defect, not caution.
- [ ] No hashing of `content` or `response_excerpt` anywhere.
- [ ] Every exception-handling site this task introduces or touches has been assessed against the
      §3.4 table, and each site that can echo input logs a **sanitized message** (class, location/
      offset, input length) while logging the stack trace unchanged. The assessment is recorded in
      the handover, site by site.
- [ ] No wrapping exception constructed by application code interpolates a raw value into its
      message.
- [ ] `notification.delivery.attempt.latency` exists as a timer tagged `outcome` and
      `status_class`, measured around the outbound HTTP call in the webhook client adapter and
      covering connect plus read.
- [ ] `notification.relay.dispatch.latency` exists as an untagged timer bounding one poll cycle
      from the claim query to the last publish returning.
- [ ] **No bespoke ingest timer is added**; `http.server.requests` is relied on.
- [ ] No histogram or SLO-bucket configuration appears in this task's diff.
- [ ] **Cardinality rule** (ADR-008 §4.3, restated here because this task registers meters):
      `client_id` and `subscription_id` are never a tag, label or attribute on any counter, gauge,
      timer or distribution summary — they are trace and log dimensions only. The same applies to
      `delivery_id`, `event_id`, a URL, an error message, and a raw HTTP status as opposed to its
      class. Neither new timer carries any of these. TASK-009-03's test will fail the build if one
      does.
- [ ] No `synchronized` block introduced; no lock held across the timed HTTP call.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A09 — the log store never holds client payload while
      staying queryable by identifier; A05 — attribute values are never concatenated into a query;
      A10 — nothing here swallows an exception silently to keep a path open).

## Verification for this task

`./gradlew compileJava compileTestJava`. **Do not run `./gradlew test` or `./gradlew build`** —
FEAT-009 runs a single full build at the end, in **TASK-009-04** (moved from TASK-009-03 when
TASK-009-04 was added).

**Amended 2026-09-21:** the no-unit-tests override was extended by the Tech Lead from TASK-009-01
to **all of FEAT-009**, along with a suspension of Testcontainers work. The unit-test clauses in the
Acceptance Criteria above ("a unit test asserts…") are therefore **not** conditions of this task and
were not conditions of it as delivered. They stand as the criteria a later test pass would assert,
not as gates. Do not write them and do not ask for them.

## Architect ruling at review — 2026-09-21

All four deviations reported at handover were ruled on. Full reasoning lives in the follow-up task
files where one was written.

| # | Deviation | Ruling |
|---|---|---|
| 1 | Exception-message sanitization done in `DeliveryQueueListener` rather than in `PointerMessage`, which was out of this task's file scope | **Correct for this task, wrong as a permanent home.** Accepted as delivered; **TASK-009-05** moves it to construction time. The logging-site fix protects one call site and reports the wrong exception class (a synthetic `RuntimeException`), which ADR-008 §3.4 requires to be the real one |
| 2 | `response_body_length` derived from the truncated excerpt | **Architect error in this task's own table, not an implementer deviation.** Accepted as delivered; **TASK-009-06** carries the untruncated length across the port. See the correction under §2 above |
| 3 | `event_id`/`client_id` set as soon as the `Delivery` row loads, earlier than this task implied | **Correct and better.** No conflict with ADR-008 §3.2, which says "once the row is loaded" and means the delivery row — it denormalizes both. Setting them earlier puts real identifiers on the subscription-not-found and event-not-found short-circuits, and feeds §5's span attributes on those paths too. Only the zero-row-claim and delivery-not-found short-circuits stay blank, correctly: no row was loaded. Task table clarified above |
| 4 | Generic `catch (Throwable t)` blocks left logging full stack traces | **Confirmed correct**, on a firmer basis than the one reported. The load-bearing reason is not "no parse exception reaches a logger" but **the deliveries and DLQ queues carry pointers, not payloads** (ADR-002, ADR-004 §1) — a Jackson exception quoting a pointer envelope quotes a `delivery_id`/`subscription_id`/`attemptHint`/`traceparent`, all identifiers, all exempt under §3.3. Spot-checked against §3.4's table below |

**§3.4 exception-type spot check, recorded so it is not re-derived:**

| §3.4 category | Where it could land | Finding |
|---|---|---|
| Jackson `JsonParseException` / `JsonMappingException` | `DeliveryQueueListener.handle` (via `PointerMessage`), `DeliveryDlqConsumer.processMessage` | The listener's is sanitized (and moves to construction in TASK-009-05). The DLQ consumer's are **swallowed without logging** inside `extractDeliveryIdFromBody` / `extractTraceparentFromBody` — they never reach a logger at all. Clean |
| `HttpMessageNotReadableException` / `MismatchedInputException` | Ingest `POST /internal/events` | **No `@ControllerAdvice` or `@ExceptionHandler` exists in this codebase**, so no application code logs it. Spring's `DefaultHandlerExceptionResolver` logs the wrapped Jackson message itself, at `DEBUG`. Inert at the default `INFO` root level — but it becomes a §3.3 leak the moment anyone raises `org.springframework.web` to `DEBUG`. Flagged into **TASK-009-03**, which is the task that turns OTLP log export on |
| HTTP client exceptions carrying a response body | outbound webhook POST | **Structurally closed**, not by review: `JdkWebhookClientAdapter` catches `Exception` and converts it to a `TransportFailure` before it can escape the adapter. No client-library exception ever reaches the use case or a logger |
| Validation / conversion errors echoing the rejected value | domain and application records | Audited all `IllegalArgumentException` construction sites under `domain/` and `application/`. Every interpolated value is a count, a limit, a duration or a status code. **None interpolates `content` or `response_excerpt`.** Satisfies this task's "no wrapping exception interpolates a raw value" criterion |
| SQL / persistence exceptions | `delivery_attempts` insert (carries `response_excerpt`) | Accepted. Postgres echoes column values in a `Detail: Key (col)=(value)` line only for constraint violations, and no constraint exists on `response_excerpt`; "value too long for type" does not quote the value. **Standing caveat: adding a unique or check constraint on `response_excerpt` or `content` would open this**, and any such migration must be reviewed against §3.4 |

Two things were also confirmed correct beyond the reported deviations: the relay precedence
inversion in `DispatchPendingDeliveriesUseCaseImpl` now reads
`delivery.traceContext().or(() -> currentTraceparent)`, matching ADR-008 §2.3; and the
`notification.delivery.attempt.latency` timer carries `outcome`/`status_class` only, with
`status_class` bucketed to `<n>xx` rather than a raw status, satisfying §4.3.

## Definition of Done

Code written, compiles clean. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
