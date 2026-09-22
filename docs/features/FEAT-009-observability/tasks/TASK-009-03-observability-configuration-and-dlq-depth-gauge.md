---
id: TASK-009-03
feature: FEAT-009
title: OTLP log export, histogram and SLO properties, meter rename, cardinality MeterFilter and its build-failing test, health probe groups, and the DLQ depth gauge
status: Not Started
agent: devops-engineer
depends_on: [TASK-009-01, TASK-009-02]
date: 2026-09-21
---

# TASK-009-03: Observability Configuration Surface and the DLQ Depth Gauge

## Feature

FEAT-009

## Assigned Agent

`devops-engineer` — this task is only for this agent.

**Boundary note, from ADR-008's Downstream section.** Parts of this task are Java in `adapter/out`
rather than configuration: the `MeterFilter` bean and its test, and the DLQ depth gauge's
scheduled poll and gauge registration. ADR-008 assigns them here **as a single unit rather than
splitting them**, because splitting a gauge from the property that schedules it, or a filter from
the property surface it guards, produces two tasks neither of which is independently verifiable.
Write that Java yourself; do not hand it off, and do not widen beyond the file list.

## Sizing exception — read this first

This task deliberately exceeds the ~3-files/one-concern rule, per the one-off exception recorded
in ADR-008's Consequences and granted by the Tech Lead for FEAT-009 only. Not a precedent.

~~**This task also owns the feature's single build and test pass**~~ — **corrected 2026-09-21: the
feature's single full `./gradlew build` moves to TASK-009-04**, which was added after TASK-009-01's
review and is now the last task of FEAT-009. This task still runs the `./gradlew build` described
below, because its own cardinality test and its runtime checks need it; it is simply no longer the
feature's final gate. That compensating control for the compression now sits in TASK-009-04.

## Scope

- File(s):
  - `src/main/resources/application.yaml` (modified — `management.*` appears **nowhere** today)
  - `src/main/resources/application-local.yaml` (modified, only if a profile-specific value is genuinely needed)
  - `src/main/java/com/cobre/challenge/adapter/out/observability/ObservabilityMeterConfig.java` (new — the `MeterFilter` bean)
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/DlqDepthGauge.java` (new — the scheduled `GetQueueAttributes` poll and its gauge), beside the existing SQS adapters
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryDlqConsumer.java` (modified — **one constant**, the meter rename)
  - `src/test/java/.../MeterCardinalityTest.java` (new — the build-failing cardinality test)
  - optionally `logback-spring.xml` **only if** the appender genuinely cannot be enabled by property alone
- Concern: the observability configuration surface, plus the two Java pieces ADR-008 binds to it.

## Ground truth — the defects this task fixes

Verified against commit `d117557`.

| # | Location | Current state |
|---|---|---|
| 8a | `src/main/resources/application.yaml`, `application-local.yaml` | **`management.*` appears nowhere** (verified by grep). `management.endpoint.health.probes.enabled` is unset, the liveness and readiness endpoints are not exposed, and the group membership ADR-002 §3.1 specifies is unconfigured |
| 8b | the same files | **no `logback-spring.xml`, no `logging.structured.*`, no `management.otlp.logging.*` anywhere.** Application logs are plain text on stdout; **Loki receives nothing** |
| 9 | `DeliveryDlqConsumer:53` | `private static final String DLQ_ARRIVAL_COUNTER = "delivery.dlq.arrival";` — the **only** meter outside the `notification.` prefix |
| 7b | anywhere | no gauge of any kind exists; DLQ **depth** is invisible, because LocalStack SQS publishes no CloudWatch-style queue-depth metric |

## What to change

### 1. OTLP structured log export (§3.1)

Log records go over OTLP to the **same collector endpoint already service-connected** for traces
and metrics, via the OpenTelemetry Logback appender autoconfigured by
`spring-boot-starter-opentelemetry`. **No new dependency.** Nothing new in `compose.yaml`, nothing
new in `TestcontainersConfiguration`.

**The plain console appender stays enabled alongside OTLP export, in every profile.** It is the
only thing that works before the SDK initializes, after it shuts down, and when the collector is
unreachable, and it costs nothing.

**The property spelling is the one unverified fact in ADR-008.** Its confidence note is explicit:
in Boot 3.4/3.5 the keys are `management.otlp.logging.export.enabled` plus
`management.otlp.logging.endpoint`; Boot 4 reorganized parts of the OpenTelemetry namespace under
`management.opentelemetry.*`. **Confirm the Boot 4.1.1 key against the reference documentation (or
`META-INF/spring-configuration-metadata.json` in the resolved jars on the Gradle cache, the method
ADR-008 §5 used for the health key) before writing it.** The mechanism is not in doubt; the key is.

**A property that parses and silently does nothing is the expected failure mode here, and it is a
failing acceptance criterion, not a cosmetic one.** Verify log records actually arrive in the LGTM
stack before marking this task ready.

**Failure behavior (A10):** the appender's batch processor is bounded and drops on overflow. It
**must never block the calling thread and must never propagate an exception into application
code.** A logging backend that can fail a delivery is a worse outcome than a lost log line.

Introduce `logback-spring.xml` only if the appender cannot be enabled by property alone; if one is
needed it is minimal and **does not redefine the console appender**.

#### 1.1 Log levels are now a §3.3 redaction surface — added 2026-09-21 by architect ruling on TASK-009-02

Turning on OTLP export makes every emitted record a record **in Loki**, including framework records
this project did not write. One of them leaks client payload:

**There is no `@ControllerAdvice` or `@ExceptionHandler` anywhere in this codebase.** A malformed
`POST /internal/events` body is therefore resolved by Spring's `DefaultHandlerExceptionResolver`,
which logs the wrapped Jackson message — and that message **quotes the raw request body**, which is
`notification_events.content`. This is exactly the ADR-008 §3.4 bypass, in framework code that no
application-side sanitization can reach.

It is inert today only because that resolver logs at `DEBUG` and the root level is `INFO`. So, as
hard constraints on this task:

- **Do not set `org.springframework.web`, `org.springframework.http`, or the root logger to `DEBUG`
  or `TRACE` in any profile**, including `local`. ADR-008 §3.3 is explicit that the redaction rule
  binds "at any level including `DEBUG` and `TRACE`" — a `DEBUG` line is still a line in Loki.
- If a debug level is genuinely needed to verify the appender during this task, scope it to a
  package that cannot carry payload (`io.opentelemetry`, `com.cobre.challenge.adapter.out.tracing`)
  and **do not commit it**.
- Any explicit `logging.level.*` entry this task adds is listed in the handover with the reason it
  cannot echo `content` or `response_excerpt`.

This constrains the task; it adds no work to it. A proper `@ControllerAdvice` that returns a
sanitized ingest error is a separate concern and is **not** in this feature — do not write one here.

### 2. The meter rename (§4.1)

`delivery.dlq.arrival` at `DeliveryDlqConsumer:53` becomes **`notification.delivery.dlq.arrival`**.
Nothing consumes it yet, which is exactly why now is the only cheap moment. One constant.

### 3. Percentile histograms and SLO buckets (§4.2)

**Enabled per meter, not globally.** A global `management.metrics.distribution.percentiles-histogram.all=true`
would attach a full bucket set to every timer in the process, including every Boot-internal one —
cardinality paid for meters nobody queries.

| Meter | Histogram | SLO buckets |
|---|---|---|
| `notification.delivery.attempt.latency` | yes | aligned to ADR-004 §1's per-attempt budget (2s connect, 5s read, ~9.3s worst case): **100ms, 250ms, 500ms, 1s, 2s, 5s, 7s, 10s**. The buckets straddle the timeout boundaries so "slow but inside budget" and "timed out" are distinguishable in the histogram itself |
| `notification.relay.dispatch.latency` | yes | aligned to the 5s poll interval: **50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s**. A cycle above the poll interval is the signal |
| `http.server.requests` | yes, **scoped to this application's URIs** | Boot defaults plus an explicit maximum-expected-value |

**Only histogram buckets are published — never client-side-precomputed `percentiles=...`.** Mimir
and Grafana compute arbitrary quantiles at query time. This is restated because **the two
properties are one character apart in the configuration and produce different, non-interchangeable
results**: `percentiles-histogram` is correct, `percentiles` is not.

### 4. The cardinality rule and its enforcement (§4.3)

**Rule, applying to every meter now and in future, with no exception:** `client_id` and
`subscription_id` are never a tag, label or attribute on any counter, gauge, timer or distribution
summary. They are trace and log dimensions only. Any other unbounded-cardinality value
(`delivery_id`, `event_id`, a URL, an error message, a raw HTTP status as opposed to its class) is
covered by the same rule for the same reason.

1. **A `MeterFilter` bean, in every profile, that `deny`s any meter whose id carries a `client_id`
   or `subscription_id` tag key.** **`deny`, not strip:** a missing panel is noticed, a silently
   merged series is not. ADR-002 §3.1's objection stands and is acknowledged — the in-process
   cardinality cost is paid before the filter runs — which is exactly why **the filter is the
   backstop and not the control**.
2. **A test that fails the build** if any meter registered during the pipeline integration run
   carries either tag key. **This is the actual control**: it runs every build, it catches the tag
   at the moment it is written, and it does not depend on a reviewer.

A denied meter loses a series and **never fails a request** (A10).

Per-client drill-down remains a Loki/Tempo operation against the `client_id` field TASK-009-02 put
on every log line, at no cardinality cost. If a standing per-client dashboard is ever genuinely
required, the path is a **log-derived recording rule, never a metric label.**

### 5. The DLQ depth gauge (§4.1.1)

`notification.delivery.dlq.arrival` counts messages **as they arrive**. It answers "did anything
land in the DLQ" but not "how much is sitting in the DLQ right now", which is the question an
operator actually asks — and it resets on a process restart while the queue does not.
**LocalStack SQS exposes no CloudWatch-style queue-depth metric**, so unlike real AWS there is
nothing to scrape and the depth must be produced by the application.

- **Mechanism:** a scheduled poll of `GetQueueAttributes` against the DLQ queue URL, reading
  `ApproximateNumberOfMessages`, published as a Micrometer gauge named
  **`notification.delivery.dlq.depth`**.
- **Interval:** a fixed delay on the order of the relay poll interval **or slower**.
  `GetQueueAttributes` is a billed API call in real AWS, and a depth that is minutes stale is still
  actionable; a sub-second gauge is not worth the call volume.
- **Attributes:** `ApproximateNumberOfMessages` only. `ApproximateNumberOfMessagesNotVisible` may
  be read alongside it, but **only one gauge is decided** — a second meter is a follow-up if the
  in-flight count turns out to matter (YAGNI).
- **Tags: none.** The queue is fixed and singular; a `queue` tag would be a constant label.
- **Placement: `adapter/out`, beside the existing SQS adapters**, next to `DeliveryDlqConsumer`'s
  queue configuration. **No use case is involved and no new port is introduced** — this reads
  infrastructure state and reports it to infrastructure, never crossing into the application layer.
  A port with one adapter and no domain caller is what the hexagonal rule does not require and
  YAGNI forbids.
- **Failure behavior (A10):** an SDK failure or a timeout **must not throw out of the scheduled
  method, must not disturb the DLQ consumer**, and must be logged at `WARN` with the exception
  handled per §3.4 (see TASK-009-02 — an SDK exception message can echo a response body). The gauge
  keeps reporting its last known value, or `NaN` if it has never succeeded. **A broken metric must
  never become a broken service.**
- **Virtual threads:** a blocking SDK call on a scheduled thread. Nothing `synchronized`; the
  gauge's backing value is a plain `AtomicLong`/`AtomicDouble` read by the meter registry, so there
  is **no pinning risk and no lock shared with the delivery path**.

### 6. Health probe groups (§5)

This is a **local-only deployment** — Docker Compose dev services, Testcontainers in tests, no
Kubernetes anywhere in this project. Nothing here is blocked by or conditional on environment
auto-detection.

**`management.endpoint.health.probes.enabled` is the correct spelling in Spring Boot 4.1.1**,
confirmed by ADR-008 §5 against `META-INF/spring-configuration-metadata.json` in
`spring-boot-health-4.1.1.jar` on this project's Gradle cache. **This one needs no further
confirmation by the implementer** (unlike the OTLP logging key above).

| Endpoint | Group members | Fails when |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` **only** | the context is broken or shutting down. **Never** because of Postgres, SQS or any other external dependency |
| `/actuator/health/readiness` | `readinessState`, **`db`** | Postgres is unreachable, or the context is not ready to serve |
| `/actuator/health` | aggregate | operator surface |

- **`db` on readiness:** a Postgres outage means this instance cannot durably accept an event
  (ADR-002 §1.1) nor claim or write a `deliveries` row. It should stop receiving traffic.
- **`db` not on liveness:** restarting every instance because a shared database is down fixes
  nothing, turns a dependency outage into a restart storm, and destroys in-flight work and warm
  pools that would otherwise recover the moment the database returns.
- **SQS on neither, deliberately:** rows stay `PENDING` and the relay re-publishes when SQS
  returns (ADR-001 §1). Failing readiness on SQS would pull every instance out of rotation
  including the ingest path, converting a recoverable degradation into a full outage. If an SQS
  check is ever wanted it belongs in the `/actuator/health` **aggregate** as informational, never
  in a probe group.

`management.endpoint.health.show-details` stays **`never`**. **This task changes no security
configuration**; it only populates groups behind paths ADR-007 §2 chain 1 already opened.
**Confirm the three probe paths are actually permitted by the delivered chain
(`SecurityConfig`, TASK-008-21) rather than assuming it.**

## Out of Scope

- **Any trace, span, propagator or replay change** — TASK-009-01.
- **Any MDC key, redaction, `toString()` or exception-sanitization change** — TASK-009-02, except
  that this task's own new `catch` block (the gauge poll) follows §3.4's rule.
- **Registering the two latency timers** — TASK-009-02 registers them; this task configures their
  distribution only.
- **Any security configuration change.** Confirm the probe paths; change nothing. If they are not
  permitted, report it and stop rather than editing `SecurityConfig`.
- The Postgres pipeline gauges (outbox depth by state, oldest-`PENDING` age, retry distribution) —
  ADR-008 §6 defers them explicitly. The **SQS DLQ depth gauge is not among them and is in scope**.
- A second gauge for `ApproximateNumberOfMessagesNotVisible`.
- Alert rules, Grafana dashboards, sampling policy, log retention, Loki tenancy, or a
  `service.namespace` / `deployment.environment` resource attribute.
- Any new dependency in `build.gradle`. There is none to add.
- Any migration.

## Acceptance Criteria

- [ ] The Boot 4.1.1 OTLP logging property key was **confirmed against the reference documentation
      or the resolved jar's configuration metadata** — not assumed — and the confirmed key is named
      in the handover.
- [ ] **Log records actually arrive in the LGTM stack**, verified by running the application and
      looking. A property that parses and does nothing **fails** this task.
- [ ] The plain console appender is still enabled in **every** profile.
- [ ] The log appender never blocks the calling thread and never propagates an exception into
      application code.
- [ ] `logback-spring.xml` exists **only if** the appender could not be enabled by property alone;
      if present it is minimal and does not redefine the console appender.
- [ ] **No profile sets `org.springframework.web`, `org.springframework.http` or the root logger to
      `DEBUG` or `TRACE`** (§1.1): `DefaultHandlerExceptionResolver` logs the Jackson message for a
      malformed ingest body at `DEBUG`, and that message quotes the raw body — a §3.3 leak straight
      into Loki once export is on. Every `logging.level.*` entry this task adds is listed in the
      handover with the reason it cannot echo `content` or `response_excerpt`.
- [ ] `DeliveryDlqConsumer:53`'s `delivery.dlq.arrival` is renamed to
      `notification.delivery.dlq.arrival`, and **no meter name anywhere lacks the `notification.`
      prefix**.
- [ ] `management.*` now exists in `application.yaml` — it appeared nowhere before this task.
- [ ] Percentile histograms are enabled **per meter**; `percentiles-histogram.all` is **not** set.
- [ ] `notification.delivery.attempt.latency` carries SLO buckets 100ms, 250ms, 500ms, 1s, 2s, 5s,
      7s, 10s.
- [ ] `notification.relay.dispatch.latency` carries SLO buckets 50ms, 100ms, 250ms, 500ms, 1s, 2s,
      5s.
- [ ] `http.server.requests` has a histogram scoped to this application's URIs plus an explicit
      maximum-expected-value.
- [ ] **No `percentiles=` property is set on any meter** — only `percentiles-histogram` and
      `slo`. The two are one character apart and are not interchangeable.
- [ ] A `MeterFilter` bean active **in every profile** `deny`s (does not strip) any meter carrying
      a `client_id` or `subscription_id` tag key.
- [ ] **A test fails the build** if any meter registered during the pipeline integration run
      carries either tag key. This test is the control; the filter is the backstop.
- [ ] `notification.delivery.dlq.depth` exists as an **untagged** gauge fed by a scheduled
      `GetQueueAttributes` poll of `ApproximateNumberOfMessages`, at a fixed delay **no faster than
      the relay poll interval**.
- [ ] The gauge lives in `adapter/out` beside the SQS adapters. **No new port, no use case, no
      application-layer type is introduced for it.**
- [ ] A failing `GetQueueAttributes` call **does not throw out of the scheduled method**, does not
      disturb `DeliveryDlqConsumer`, logs at `WARN` with a §3.4-sanitized message, and leaves the
      gauge at its last value or `NaN`.
- [ ] No `synchronized` anywhere in the gauge; its backing value is an atomic and shares no lock
      with the delivery path.
- [ ] `management.endpoint.health.probes.enabled` is `true`; the **readiness** group contains
      `readinessState` and `db`; the **liveness** group contains `livenessState` and **not** `db`;
      **SQS is in neither group**.
- [ ] `management.endpoint.health.show-details` is `never`.
- [ ] The three probe paths were **confirmed permitted by the delivered `SecurityConfig`**
      (TASK-008-21), and no security configuration was changed.
- [ ] **Cardinality rule** (ADR-008 §4.3, restated here because this task registers a meter):
      `client_id` and `subscription_id` are never a tag, label or attribute on any counter, gauge,
      timer or distribution summary — trace and log dimensions only. The same applies to
      `delivery_id`, `event_id`, a URL, an error message, and a raw HTTP status as opposed to its
      class. The new gauge is untagged.
- [ ] **No new dependency was added to `build.gradle`.**
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A02 — no new listener, port or credential, and the probes
      are no longer an unconfigured endpoint; A03 — no new dependency; A09 — logs reach a queryable
      store and DLQ depth becomes alertable; A10 — every failure path leaves the service intact).

## Verification for this task — the feature's single build and test pass

This is the **last task of FEAT-009 and the one that runs the full verification**, which is the
compensating control for this feature's compressed task granularity (ADR-008 Consequences).

1. `./gradlew build` — the single full build and test pass for the whole feature, covering
   TASK-009-01 and TASK-009-02 as well.
2. Run the application (`bootRun`, `local` profile, Docker up) and confirm by inspection:
   **log records in Loki**, the **three latency/histogram meters in Mimir**, a **single continuous
   trace** spanning ingest, dispatch and attempt in Tempo, the **DLQ depth gauge** reporting, and
   `/actuator/health/liveness` and `/actuator/health/readiness` both responding.
3. Report any failure attributable to TASK-009-01 or TASK-009-02 back to the Tech Lead rather than
   fixing it here — a fix in this task's diff would defeat the review boundary.

## Definition of Done

Configuration and the two Java pieces written, the full build passing, and the runtime checks in
step 2 observed. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
