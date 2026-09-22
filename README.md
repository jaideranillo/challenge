# challenge

Spring Boot 4.1.1 / Java 21, hexagonal architecture. See `CLAUDE.md` for the architecture and
delivery workflow, and `docs/architecture/adr/` for the design decisions behind this service.

## Option 1: run everything with `make`

This is the fastest path to a fully working stack: infra, app, and demo data in one command.

```bash
make up
```

This starts, in order:

1. **Infra containers** (`docker compose up -d`): Postgres, LocalStack (SQS), and the
   `grafana/otel-lgtm` stack (Grafana + Loki + Tempo + Mimir).
2. **The app**, in the background, with `--spring.profiles.active=local` and
   `CHALLENGE_WEBHOOK_SECRETS_DEMO` set to a demo value.
3. **A health wait loop** against `http://localhost:8080/actuator/health`.
4. **A demo data seed** (`tools/dev-seed/seed-subscriptions.sh`), idempotent, safe to rerun.

Once it's up:

- App: `http://localhost:8080`
- Grafana: `http://localhost:3000`
- Logs: `make logs`
- Stop everything: `make down`

Other targets: `make restart`, `make status`, `make test`, `make build`, `make clean`.

Requires Docker running. `make up` builds nothing beyond the JVM process itself: the app runs
directly via `./gradlew bootRun`, it is not containerized. See `CLAUDE.md` if you want to discuss
adding an app image to the compose stack; that's an architecture decision, not a Makefile tweak.

## Option 2: run locally from IntelliJ

Use this when you want to run/debug the app from the IDE (breakpoints, hot reload) instead of the
backgrounded `make up` process.

### 1. Start infra only

The app itself is not part of `compose.yaml`'s services meant to be started this way; only bring up
the dev dependencies:

```bash
make infra-up
```

This starts Postgres, LocalStack, and grafana-lgtm and leaves them running. `make down` stops them
later (it also tries to stop an app process started by `make up`, which is harmless if you ran the
app from IntelliJ instead).

### 2. Configure the run configuration

Run `ChallengeApplication` (or `TestChallengeApplication` if you'd rather let Testcontainers manage
infra instead of compose, see below) with:

- **Active profiles**: `local`
- **Environment variables**: `CHALLENGE_WEBHOOK_SECRETS_DEMO=demo-secret-value` (any non-blank
  value; deliveries fail closed without it)

In IntelliJ Ultimate: Run > Edit Configurations > select the run configuration > set "Active
profiles" to `local` under the Spring Boot tab, and add the env var under "Environment variables".

In IntelliJ Community (no Spring Boot tab): Run > Edit Configurations > select the run
configuration > set:
- **VM options**: `-Dspring.profiles.active=local`
- **Environment variables**: `CHALLENGE_WEBHOOK_SECRETS_DEMO=demo-secret-value`

(If "VM options" isn't visible, click "Modify options" and enable it first.)

### 3. Seed demo data (optional)

Only needed if you want the pre-loaded demo client subscriptions (`CLIENT001/002/003`):

```bash
./tools/dev-seed/seed-subscriptions.sh
```

Requires the compose Postgres running (step 1).

### 4. Issue a dev JWT (optional, for calling authenticated endpoints)

The `local` profile trusts a static test key, not the corporate IdP:

```bash
./tools/dev-jwt/issue-token.sh
```

See `tools/dev-jwt/README.md` for usage details.

### 5. Call the API with Insomnia (optional)

Import `tools/insomnia/challenge-collection.json` into Insomnia (Application > Preferences > Data >
Import Data, or drag the file onto the app). It brings the "Cobre Challenge" workspace with:

- A **Local** environment, pre-set with `base_url = http://localhost:8080`. Select it from the
  environment dropdown before sending requests.
- Request folders matching the adapters: **Webhook Stub** (local profile only, inspects/controls
  the fake receiver), **Event Generator** (local profile only, bulk-seeds synthetic notification
  events), **Gateway Ingest** (the ADR-002 ingest endpoint), and **Self-Service API** (ADR-005/007
  subscription/notification management).

Authenticated requests read a `jwt_token` environment variable. Run `./tools/dev-jwt/issue-token.sh`
(step 4 above), paste the token into the Local environment's `jwt_token` value, and requests that
need auth will pick it up automatically.

### Alternative: skip compose entirely with Testcontainers

Run `TestChallengeApplication` instead of `ChallengeApplication` (still with `--spring.profiles.active=local`
if you want the demo timings/egress allowlist). `TestcontainersConfiguration` spins up
`PostgreSQLContainer` and `LgtmStackContainer` and wires the datasource/OTel config automatically,
no `compose.yaml` or `make infra-up` needed. Useful when you don't want the compose stack running in
parallel with something else.

## Generate traffic and validate in Grafana

With the stack up (either option above) and demo subscriptions seeded (`./tools/dev-seed/seed-subscriptions.sh`),
drive synthetic load through the full pipeline (ingest → relay → SQS → worker → webhook):

```bash
curl -X POST http://localhost:8080/local/event-generator/generate \
  -H 'Content-Type: application/json' \
  -d '{"count": 30}'
```

For a sustained run instead of a single burst, send several batches spaced out so the relay (polls
every 2s in the `local` profile) and worker drain each batch before the next lands, e.g. 4 batches
of 30, 30-40s apart:

```bash
for i in 1 2 3 4; do
  curl -s -X POST http://localhost:8080/local/event-generator/generate \
    -H 'Content-Type: application/json' -d '{"count": 30}'
  sleep 35
done
```

Then validate in Grafana (`http://localhost:3000`, dashboard **Notification Delivery**, auto-provisioned):

- **Prometheus** panels update on a ~60s delay (`OtlpMeterRegistry` pushes metrics every 60s, not
  on every request) — set the time range to "Last 15 minutes" and refresh if a panel looks flat
  right after sending traffic.
- **Loki** ("Trace and log correlation" panel, or Explore > Loki): query `{service_name="challenge"}`.
  Every log line carries `trace_id`/`span_id`, click-through to Tempo confirms the correlation.
- A successful run shows `Attempt outcome=SUCCESS http_status=200` in the app log
  (`.run/app.log` under `make up`) for every claimed delivery — that's the worker actually reaching
  the local webhook stub, not just the relay claiming rows.

### Useful Explore queries

**Loki** — Explore > datasource **Loki**, paste in the query field (label selector is required,
Loki returns nothing with an empty query):

```logql
{service_name="challenge"}
```

Filter to one delivery's full trail across the pipeline:

```logql
{service_name="challenge"} |= "delivery_id=<uuid>"
```

**Prometheus** — Explore > datasource **Prometheus**:

```promql
# confirms the claimDue / worker pipeline is producing attempts at all
notification_delivery_attempt_latency_milliseconds_count

# p99 delivery attempt latency
histogram_quantile(0.99, sum(rate(notification_delivery_attempt_latency_milliseconds_bucket[5m])) by (le))

# relay claim/publish activity (app log, not a metric) - grep instead
# grep "Relay cycle" .run/app.log
```

## Tests

```bash
./gradlew test
```

Adapters run against real Postgres and LocalStack via Testcontainers, no mocks, no H2. Domain logic
is plain JUnit with no Spring context. Requires Docker running.

## Logging → Loki

Application logs reach Loki via `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`
(`build.gradle`), wired in `src/main/resources/logback-spring.xml` and installed onto the Spring
`OpenTelemetry` bean by `OpenTelemetryLoggingInstaller` on `ApplicationReadyEvent`. Log lines emitted
before the app finishes starting are not exported (negligible in practice, no request traffic that
early). `trace_id`/`span_id` are attached automatically from the live OTel context; the MDC keys
`delivery_id`, `event_id`, `client_id`, `subscription_id` are captured explicitly (ADR-008 §3.2).
