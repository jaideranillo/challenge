---
id: FEAT-009
title: Observability — trace continuity across the SQS hop, OTLP structured logs, latency meters and histograms, health probes
status: Planned
adr: ADR-008
date: 2026-09-21
authors: software-architect (Atlas)
---

# FEAT-009: Observability

## Source ADR

ADR-008 was verified `Accepted` by reading both its `status:` front matter and its `## Status`
section before this file was written. **No `Status` field was touched and no ADR body was amended
by this feature.**

| ADR | Status | What this feature takes from it |
|-----|--------|---------------------------------|
| ADR-008 | Accepted | §2 in full (propagator-based inject/extract, inverted relay precedence, named spans, replay span link), §3 (OTLP log export, MDC ownership, redaction, exception-message sanitization), §4 (meters, latency timers, histograms, the DLQ depth gauge, the cardinality rule and its enforcement), §5 (health probe groups) |
| ADR-002 | Accepted | §3 / §3.1 — the observability *shape* this ADR implements. Not reopened |
| ADR-003 | Accepted | Amendment A3 — `deliveries.trace_context` is an existing column and a `Delivery` component. **No migration** |
| ADR-004 | Accepted | §1's per-attempt timeout budget (2s connect, 5s read) — the source of the attempt-latency SLO buckets; §1's envelope, unchanged |
| ADR-005 | Accepted | §1's replay-as-insert — the row whose `trace_context` stops being `Optional.empty()` |
| ADR-007 | Accepted | §2 chain 1 already permits the three health paths and gates every other actuator endpoint behind `ops`. **No security configuration changes here** |

No design decision is reopened, re-proposed or questioned here.

## Scope (MVP / Post-MVP)

### In scope

1. **Trace continuity across the SQS hop** (ADR-008 §2) — `Propagator`-based inject on publish and
   extract on receive, `deliveries.trace_context` as the fallback carrier, the five named spans,
   and the replay span link.
2. **The relay precedence inversion** (§2.3) — the row's own `trace_context` wins over the
   scheduler's context, which is the single change that makes "one trace per business flow" true.
3. **Replay trace context** (§2.5) — the replay row carries the replay request's traceparent;
   the original's trace is attached as a span link.
4. **OTLP structured log export** (§3.1) through the OpenTelemetry Logback appender, with the
   plain console appender kept in every profile.
5. **MDC keys and their ownership** (§3.2) — `event_id` and `client_id` added, the hand-set
   `trace_id` deleted, and the MDC scope moved to the outermost adapter.
6. **Redaction** (§3.3) and **exception-message sanitization** (§3.4).
7. **Three latency meters and their histograms** (§4.1, §4.2), the `delivery.dlq.arrival` rename,
   the **DLQ depth gauge** (§4.1.1), and the **cardinality `MeterFilter` plus its build-failing
   test** (§4.3).
8. **Health probe groups** (§5) — `management.endpoint.health.probes.enabled`, a readiness group
   containing `db`, a liveness group that does not.

### Deferred (named in ADR-008 §6, not built here)

- The **Postgres** pipeline gauges: outbox depth by state, age of the oldest `PENDING` row, and
  the retry-count distribution. Each needs a repository query behind it; the SQS DLQ depth gauge
  is **not** among these and **is** in scope.
- Alert rules. **Grafana dashboard provisioning was added to this feature as TASK-009-07** (see the
  Task Breakdown); alert rules remain deferred.
- Any sampling policy. Everything stays at 100%.
- Log retention, Loki tenancy, collector-side configuration.
- A `service.namespace` / `deployment.environment` resource attribute.
- **No migration, no table, no column, no index.** `deliveries.trace_context` already exists
  (`V2__deliveries.sql`).

## What is already merged, and what is wrong with it

Every row below is verified against the source tree at commit `d117557` and is the ground truth
each task's acceptance criteria refer back to. **An implementer verifies against this table, not
against a re-derivation from prose.**

| # | Location | State | Owning task |
|---|---|---|---|
| 1 | `AttemptDeliveryUseCaseImpl:181` | `command.traceparent().or(delivery::traceContext).ifPresent(traceId -> MDC.put(MDC_TRACE_ID, traceId));` — puts a **whole traceparent** (`00-<32 hex>-<16 hex>-<flags>`) into MDC under `trace_id`, and never activates the OTel context. **Deleted** per §2.4 | 01 |
| 2 | `DispatchPendingDeliveriesUseCaseImpl:67-72` | `currentTraceparent.or(delivery::traceContext)` — **precedence inverted**. One poll cycle's context is stamped onto up to 500 unrelated rows (`challenge.relay.batch-limit`) | 01 |
| 3 | `ReplayDeliveryUseCaseImpl:58-71` | the replay `Delivery` is built with `Optional.empty()` in the `traceContext` position — a replay has no trace link, forward or backward | 01 |
| 4 | `MicrometerTraceContextAdapter` | hand-formats `"00-" + traceId + "-" + spanId + "-" + flags`. Correct today, but a private reimplementation of a wire format the SDK owns. Moves to `Propagator` inject (§2.1); **`TraceContextPort`'s signature does not change** | 01 |
| 5 | anywhere | **No** `Propagator`/`TextMapPropagator` `extract` call exists. No `Observation`, no `@Observed`, no `nextSpan`, no `Tracer` outside `MicrometerTraceContextAdapter`, which only reads. The five named spans of §2.6 do not exist | 01 |
| 6 | `AttemptDeliveryUseCaseImpl` | sets `delivery_id`, `subscription_id`, `attempt_number`, `status_class`, `trace_id`, and calls `MDC.clear()` in its `finally` — clearing keys it does not own. `event_id` and `client_id`, both required by ADR-002 §3, are **never** set anywhere | 02 |
| 7 | anywhere | **No** `Timer`, **no** `DistributionSummary`, **no** `Gauge` of any kind. Zero latency meters | 02 (timers), 03 (gauge) |
| 8 | `src/main/resources/application.yaml`, `application-local.yaml` | **`management.*` appears nowhere** (verified by grep). Probes unconfigured, groups undefined. No `logback-spring.xml`, no `logging.structured.*`, no `management.otlp.logging.*` — application logs are plain text on stdout and Loki receives nothing | 03 |
| 9 | `DeliveryDlqConsumer:53` | `private static final String DLQ_ARRIVAL_COUNTER = "delivery.dlq.arrival";` — the only meter outside the `notification.` prefix. **Renamed** to `notification.delivery.dlq.arrival` | 03 |

## Architecture

```mermaid
flowchart TB
  subgraph AdapterIn["Adapter:In"]
    CTRL["EventIngestController<br/>span: notification.ingest"]
    SCHED["DeliveryRelayScheduler<br/>span: notification.relay.poll (root) - TASK-009-01<br/>+ notification.dispatch per delivery - TASK-009-04"]
    LSTN["DeliveryQueueListener<br/>propagator.extract(msg attrs)<br/>span: notification.attempt<br/>owns the MDC scope + the span scope, one finally"]
    DLQ["DeliveryDlqConsumer<br/>same extract, span: notification.dlq<br/>+ DLQ depth gauge poll"]
    SS["Replay endpoint (ADR-005)<br/>span: notification.replay + link"]
  end

  subgraph App["Application"]
    UC1["RegisterNotificationEventUseCase"]
    UC2["DispatchPendingDeliveriesUseCase<br/>precedence INVERTED (§2.3)"]
    UC3["AttemptDeliveryUseCase<br/>MDC.put event_id/client_id only<br/>NO MDC.clear, NO trace_id"]
    UC4["ReplayDeliveryUseCase<br/>traceContext = replay request's"]
    TP["port/out/tracing: TraceContextPort<br/>(one method, unchanged)"]
  end

  subgraph AdapterOut["Adapter:Out"]
    SQSP["SqsNotificationQueueAdapter<br/>propagator.inject -> traceparent attr"]
    WHC["JdkWebhookClientAdapter<br/>notification.delivery.attempt.latency"]
    JDBC["DeliveryPipelineJdbcRepository"]
    TPA["MicrometerTraceContextAdapter<br/>propagator.inject, not string concat"]
    MF["ObservabilityMeterConfig<br/>MeterFilter.deny(client_id | subscription_id)"]
  end

  PG[("PostgreSQL — deliveries.trace_context (V2, existing)")]
  SQS[["SQS deliveries + DLQ<br/>MessageAttribute: traceparent"]]
  OTLP[["OTLP 4317/4318 — Tempo + Mimir + Loki<br/>traces + metrics + LOG RECORDS (new)"]]

  CTRL --> UC1 --> TP
  TP -.impl.-> TPA
  UC1 --> JDBC --> PG
  SCHED --> UC2 --> SQSP --> SQS
  SQS --> LSTN --> UC3 --> WHC
  SQS --> DLQ
  SS --> UC4 --> JDBC
  CTRL -.spans/logs/metrics.-> OTLP
  LSTN -.spans/logs/metrics.-> OTLP
  SCHED -.spans/logs/metrics.-> OTLP
  DLQ -.dlq.depth gauge.-> OTLP
  MF -.filters.-> OTLP
```

## Port Contracts

**No `port/in` interface is added or changed. No `port/out` interface is added or changed.**

| Port | Change |
|---|---|
| `TraceContextPort.currentTraceparent()` | **Signature unchanged.** Its adapter obtains the value by injecting into a single-entry carrier instead of concatenating fields (§2.1). It does **not** grow span-creation methods — ADR-008 §2.6 keeps it a one-method read port (YAGNI, Effective Java Item 64) |
| `AttemptDeliveryCommand.traceparent()` | **Stays on the command.** The use case still needs the value for the fallback decision when it loads the row; it simply stops interpreting it as a trace id (§2.4) |
| DLQ depth gauge | **No port.** §4.1.1 is explicit: the poll reads infrastructure state and reports it to infrastructure, never crossing into the application layer. A port with one adapter and no domain caller is what the hexagonal rule does not require and YAGNI forbids |

**Span creation lives in adapters, not in use cases** (§2.6). Creating a span requires a `Tracer`,
a scope and a `finally` — framework lifecycle management, which belongs on the adapter side of the
boundary. `org.slf4j.MDC` in the application layer is permitted, as `org.slf4j.Logger` already is:
the hexagonal line here is about **lifecycle**, not about the import.

## Data Model Impact

**None.** `deliveries.trace_context` (`text`, nullable) already exists from `V2__deliveries.sql`
and is a `Delivery` component per ADR-003 Amendment A3. No migration is written by this feature
and the `dba` agent is not assigned any task in it. The only write this feature adds is a non-null
value into that existing nullable column on a replay row (§2.5), where null is already supported.

## Security Impact

**No endpoint, no authorization rule, no filter chain, and no security configuration changes.**
§5 populates health groups behind paths ADR-007 §2 chain 1 already opened, and
`management.endpoint.health.show-details` stays `never`.

| OWASP Top 10:2025 | Exposure | Where it is handled |
|---|---|---|
| **A01 Broken Access Control** | None added. No new endpoint | Probe paths already permitted by ADR-007 §2 chain 1. TASK-009-03 **confirms** against the delivered `SecurityConfig` rather than assuming |
| **A02 Security Misconfiguration** | A probe endpoint nobody configured is itself a misconfiguration | §5's property set explicitly (TASK-009-03). No new listener, port or credential — the OTLP endpoint is the one already service-connected |
| **A03 Software Supply Chain** | **No new dependency.** Appender, propagator, meter filter and health groups all ship in `spring-boot-starter-opentelemetry` and `spring-boot-starter-actuator`, both already on the classpath | A factor in choosing L-B over a collector agent |
| **A04 Cryptographic Failures** | None. No key, secret or token is read, logged or exported | ADR-004 §2 signature headers and ADR-007 §7 key material stay never-logged |
| **A05 Injection** | `client_id` / `event_id` originate with a client and reach log records and span attributes | Structured attributes on a record, never concatenated into SQL or a shell. Log-forging is bounded by structure: a newline in an attribute value stays an attribute value |
| **A06 Insecure Design** | A cardinality rule enforced by a reviewer is not a control | §4.3's build-failing test is the control; the `MeterFilter` is the backstop (TASK-009-03). `toString()` redaction is honestly weaker than a type guarantee (TASK-009-02) |
| **A07 Authentication Failures** | None added | ADR-007 §9's requirement that 401/403/429 be logged structurally becomes **operational** for the first time: before this feature those lines never left the process |
| **A09 Logging & Alerting Failures** | **The primary category.** Payload reaching Loki; the exception path bypassing redaction | §3.3 omits `content` and `response_excerpt` entirely (not truncated, not hashed); identifiers are logged in **full**; §3.4 sanitizes exception messages that echo raw input. TASK-009-02 owns both |
| **A10 Mishandling of Exceptional Conditions** | Every observability failure path | All fail open toward the business operation, closed toward the signal: a malformed traceparent starts a new root trace, a missing original context yields no span link, the appender drops on overflow and never throws, a denied meter loses a series, a failed `GetQueueAttributes` leaves the gauge stale. **None may fail a write** — and nothing here permits swallowing an exception silently to keep a path open |

No `security-engineer` task is assigned: this feature changes no security configuration. If an
implementer finds they need to, that is out of scope and goes back to the Architect.

## Task granularity — a documented one-off exception

**This feature was deliberately compressed to three tasks at the Tech Lead's explicit request**
(a fourth was added at task 01's review, see the Task Breakdown),
overriding the ~3-files/one-concern sizing rule in `docs/README.md`. This is recorded in ADR-008's
Consequences ("Process note, recorded at the user's explicit direction") and is **not a precedent
for later features.** Task 01 alone touches roughly five to seven files.

**The compensating control is a single build and test pass at the end of the feature rather than
per task.** Concretely:

- TASK-009-01 and TASK-009-02 verify with `./gradlew compileJava compileTestJava` plus the unit
  tests they write. **They do not run `./gradlew test` or `./gradlew build`.**
  **TASK-009-01 was delivered with no unit tests at all**, by explicit Tech Lead override for that
  task only. The override does not extend to any other task.
- TASK-009-03 runs `./gradlew build` for its own cardinality test and its runtime verification of
  §3.1 (log records actually arriving in the LGTM stack).
- **TASK-009-04, now the last task, owns the feature's final full `./gradlew build`.**
- Reviewers should expect a correspondingly larger diff per task.

## One property spelling is unverified, and that is a task obligation

ADR-008's confidence note is explicit: the OTLP **logging** exporter property spelling in Spring
Boot 4.1.1 is the single factual claim in the ADR that could not be verified from the repository.
The mechanism is not in doubt; the key is. TASK-009-03 confirms it against the Boot 4.1.1
reference documentation, starting from `management.otlp.logging.export.enabled` /
`management.otlp.logging.endpoint`, and **treats a property that parses and silently does nothing
as a failing acceptance criterion, not a cosmetic one.**

`management.endpoint.health.probes.enabled` (§5) needs no such confirmation — ADR-008 verified it
against `META-INF/spring-configuration-metadata.json` in `spring-boot-health-4.1.1.jar`.

## Task Breakdown

Strictly sequential. Task 02 touches `AttemptDeliveryUseCaseImpl`'s MDC block, which task 01
edits; task 03's meter filter test must run against the meters tasks 01 and 02 register.

| # | Task | Agent | Depends on |
|---|------|-------|------------|
| 01 | [Trace propagation and replay linkage](tasks/TASK-009-01-trace-propagation-and-replay-linkage.md) | backend-engineer | - |
| 02 | [Log context, redaction and latency meters](tasks/TASK-009-02-log-context-redaction-and-latency-meters.md) | backend-engineer | 01 |
| 03 | [Observability configuration surface and DLQ depth gauge](tasks/TASK-009-03-observability-configuration-and-dlq-depth-gauge.md) | devops-engineer | 01, 02 |
| 04 | [The per-delivery `notification.dispatch` span](tasks/TASK-009-04-per-delivery-dispatch-span.md) | backend-engineer | 01, 02, 03 |
| 05 | [Sanitize `UnparsablePointerMessageException` at construction](tasks/TASK-009-05-sanitize-unparsable-pointer-exception-at-construction.md) | backend-engineer | 02 |
| 06 | [True `response_body_length` diagnostic](tasks/TASK-009-06-true-response-body-length-diagnostic.md) | backend-engineer | 02 |
| 07 | [Grafana dashboard provisioning](tasks/TASK-009-07-grafana-dashboard-provisioning.md) | devops-engineer | 01, 02, 03 |

**Task 07 was added 2026-09-21.** It provisions a Grafana dashboard for the meters this feature
emits, plus the trace/log correlation §2 and §3.1 wired up. **It needs no ADR**: ADR-008 §6 defers
"Alert rules and dashboards" explicitly as "devops work with no architectural content", and ADR-008's
Consequences name dashboard work as unblocked by tasks 01-03. Every meter name, tag set and histogram
bucket it renders is already fixed by ADR-008 §4.1 and §4.2; **no design decision is opened by it.**
It depends on 01, 02 and 03 because the metrics, their histograms and the OTLP log export must exist
before anything can render them: 02 registers the two latency timers, 03 registers the DLQ depth
gauge, applies the rename and the per-meter histogram properties, and wires log records to Loki, and
01 is what makes a trace continuous enough to be worth linking to. Tasks 04, 05 and 06 add no meter,
so they neither gate it nor are gated by it. **It is three files and one concern, inside the
`docs/README.md` sizing rule** — the task-granularity exception below is not extended to it. No unit
test applies to a dashboard JSON, and task 04 still owns the feature's single full `./gradlew build`.
Alert rules stay deferred.

**Tasks 05 and 06 were added 2026-09-21, at TASK-009-02's review.** Both are §3.4/§3.3 corrections
that task 02 could not make inside its declared file scope: 05 moves the exception-cause
sanitization from the one logging site to the exception's construction, so the type is safe to log
anywhere rather than at one call site (and so the log record reports the real exception class); 06
carries the **untruncated** response body length across `WebhookClientPort`, because task 02's own
scope table wrongly claimed it was "already available" and the delivered diagnostic saturates at the
1024-character excerpt limit. **Neither reopens an ADR-008 decision; both are task-scope
corrections, one of them an architect error.** Both depend only on task 02, touch no file that tasks
03 or 04 touch, and therefore **do not block or gate either** — they can land before, alongside or
after them. Task 04 still owns the feature's single full `./gradlew build`; if 05 and 06 land after
it, they are covered by the next build.

**Task 04 was added 2026-09-21, at TASK-009-01's review.** ADR-008 §2.6's `notification.dispatch`
span could not be delivered inside task 01: it needs per-delivery data that
`DispatchPendingDeliveriesResult` does not carry out to the relay adapter, and closing that gap is
a `port/in` contract change plus a new recorder — four files and a second concern, which task 01's
scope note forbade. **No ADR decision was reopened; only the task split was corrected.** Task 04 is
last, so it now owns the feature's single full `./gradlew build` (moved from task 03).

**Boundary note on task 03** (ADR-008 Downstream). The DLQ depth gauge spans both roles the same
way the rest of task 03 does: the scheduled `GetQueueAttributes` poll and its gauge registration
are Java in `adapter/out` (backend work), while the interval and any enabling property are
configuration (devops work). It is **assigned to task 03 as a single unit rather than split**,
because splitting a gauge from the property that schedules it produces two tasks neither of which
is independently verifiable. The task file is assigned to `devops-engineer` and names the Java
boundary explicitly, as it already does for the meter filter and its test.

## The cardinality rule, restated in every task

ADR-008 §4.3 requires this to be in front of whoever adds the next meter, so it is repeated
verbatim in every task file, including TASK-009-07, where it constrains panel queries and dashboard
variables rather than meter registration:

> `client_id` and `subscription_id` are never a tag, label or attribute on any counter, gauge,
> timer or distribution summary. They are trace and log dimensions only. Any other
> unbounded-cardinality value (`delivery_id`, `event_id`, a URL, an error message, a raw HTTP
> status as opposed to its class) is covered by the same rule for the same reason.

## Status

Planned <!-- Planned | In Progress | Done -->
