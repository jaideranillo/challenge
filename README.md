# challenge

Spring Boot 4.1.1 / Java 21, hexagonal architecture. See `CLAUDE.md` for the architecture and
delivery workflow, and `docs/architecture/adr/` for the design decisions behind this service.

## Quick demo workflow

With the stack up (`make up`, see below) and subscriptions seeded (`make up` already does this;
otherwise `./tools/dev-seed/seed-subscriptions.sh`), the fastest path to seeing the whole system
work end to end, self-service API included:

**1. Generate events in bulk.** Each one is assigned a random client and the response tells you
which:

```bash
curl -X POST http://localhost:8080/local/event-generator/generate \
  -H 'Content-Type: application/json' -d '{"count": 30}'
```

Response includes, per event:

```json
{"eventId": "EVT013", "deliveryIds": ["003b1ff1-18fc-48fa-a0d2-f1441a66642b"], "clientId": "CLIENT002", "eventType": "..."}
```

Two different ids, both useful, neither is what you'd guess: `eventId` is the platform event's own
id (deliberately not UUID-shaped — see "Two ids, not one" below for why); `deliveryIds` is what
step 3's URLs actually take (one per subscription the event fanned out to; **empty if no
subscription matched** — see "Finding which client an event belongs to" below). Pick a `clientId`
from here for step 2, and a `deliveryId` (not `eventId`) for step 3.

**2. Generate a token for that client, copy the value.**

```bash
curl -X POST http://localhost:8080/local/dev-token \
  -H 'Content-Type: application/json' -d '{"clientId": "CLIENT002"}'
```

Copy the `"token"` field from the response (starts `eyJ...`). See step 4 in Option 2 below for
details (lifetime, tenant-switching), or the Insomnia "Dev Token" folder to do this from the UI
instead of curl — that one auto-fills the token for you, no copying needed.

**3. Use the self-service APIs with that token.**

```bash
curl http://localhost:8080/notification_events -H "Authorization: Bearer <token from step 2>"
curl http://localhost:8080/notification_events/<notification_event_id> -H "Authorization: Bearer <token>"
curl -X POST http://localhost:8080/notification_events/<notification_event_id>/replay -H "Authorization: Bearer <token>" -H "Idempotency-Key: <any-uuid>"
```

Only events belonging to the token's `clientId` come back — that's the tenant isolation from
ADR-007 §5, not a filter you pass. A foreign `notification_event_id` returns 404, never 403. Full
detail on each step, plus the same flow from Insomnia instead of curl, in Option 2 below.

**Pagination is cursor-based (keyset), not offset-based (`page=2`).** ADR-005 §1: offset pagination
degrades on a write-hot table (Postgres still has to scan and discard every skipped row). The
response carries `nextCursor` (opaque, base64-encoded `(event_created_at, delivery_id)` tuple, not
something to construct by hand) when there are more results:

```bash
curl "http://localhost:8080/notification_events?limit=5" -H "Authorization: Bearer <token>"
# -> {"items": [...], "nextCursor": "MTc5..."}
curl "http://localhost:8080/notification_events?limit=5&cursor=MTc5..." -H "Authorization: Bearer <token>"
# next page, ordered event_created_at DESC, delivery_id DESC as tiebreak
```

`nextCursor` is `null`/absent on the last page. Default page size is 50, max 200 (`limit` above the
max is clamped, not rejected). Every filter request in the Insomnia collection (status/date
variants below) carries `limit`/`cursor` as disabled parameters — enable them to page through a
larger result set instead of getting the whole thing in one response.

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
- Postgres: `localhost:5432` (db `mydatabase`, user `myuser`, password `secret` — connect a DB
  client like DBeaver here; schema is `public`)
- Logs: `make logs`
- Stop everything: `make down`

All ports above are pinned in `compose.yaml`, stable across restarts. (If you're on a commit
before 2026-09-23, Postgres and the Grafana OTLP ports 4317/4318 were host-random instead — every
`docker compose up` picked new ones, which silently broke the app's OTLP connection on a
Grafana-only restart and forced re-guessing the Postgres port for DB tools. Pull latest.)

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

The `local` profile trusts a static test key, not the corporate IdP. `POST /local/dev-token`
(local profile only, `adapter/in/web/local/devtoken`) mints one over HTTP, signed with that key —
requires the app already running (step 2). `clientId` decides which tenant the token is for (this
is what a real IdP would embed after authenticating the client; here you set it directly since
you're standing in for the IdP in demo mode):

```bash
curl -X POST http://localhost:8080/local/dev-token \
  -H 'Content-Type: application/json' \
  -d '{"clientId": "CLIENT001", "scope": "notifications:read notifications:replay"}'
```

Returns `{token, clientId, scope, expiresAt}`, valid 1h. Seed data (step 3) creates subscriptions
for `CLIENT001`/`CLIENT002`/`CLIENT003` — reissue with a different `clientId` to test tenant
isolation (same request, different client, only that client's own data comes back; a foreign id
returns 404, never 403).

From Insomnia: the "Dev Token" folder's request does this and auto-writes the result into the
`jwt_token` environment variable — nothing to copy (see step 5 below).

**Token lifetime math.** `exp = iat + lifetime`, where `lifetime` is whichever is smaller: what you
asked for (`lifetimeSeconds` in the request body, if given) and the configured ceiling
(`challenge.security.jwt.max-lifetime`, default **3600s / 1h**, ADR-007 §3). Requesting more than
the ceiling doesn't fail — it silently clamps to the ceiling.

To check how much time is actually left on a token you're holding, decode its middle segment
(no signature verification needed, this only reads the claims — plain `base64 -d` on the raw
segment fails on macOS/most systems because JWT uses unpadded base64url, so pad it first):

```bash
TOKEN="<paste your token>"
python3 -c "
import base64, json, time
payload = '$TOKEN'.split('.')[1]
payload += '=' * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
print(json.dumps(claims, indent=2))
print('expires in', claims['exp'] - int(time.time()), 's')
"
```

A negative result means the token already expired — the API rejects it with 401 at the decoder,
before any handler runs (§3 of the study guide, `docs/guia-estudio-comite-arquitectura.md`).

### 5. Call the API with Insomnia (optional)

Import `tools/insomnia/challenge-collection.json` into Insomnia (Application > Preferences > Data >
Import Data, or drag the file onto the app). It brings the "Cobre Challenge" workspace with:

- A **Local** environment, pre-set with `base_url = http://localhost:8080`. Select it from the
  environment dropdown before sending requests.
- Request folders matching the adapters: **Webhook Stub** (local profile only, inspects/controls
  the fake receiver), **Event Generator** (local profile only, bulk-seeds synthetic notification
  events), **Dev Token** (local profile only, mints a JWT over HTTP — the Insomnia-only path
  through step 4 above, no shell needed), **Gateway Ingest** (the ADR-002 ingest endpoint), and
  **Self-Service API** (ADR-005/007 subscription/notification management).

Authenticated requests read a `jwt_token` environment variable, referenced as
`Authorization: Bearer {{ _.jwt_token }}` on each request that needs it. The "Dev Token" folder's
"Issue Dev Token (POST)" request has an `afterResponseScript` that writes the response's `token`
straight into the **Local** environment's `jwt_token` — nothing to copy, nothing to paste. Set
`clientId` in that request's body, send it, and every request under **Self-Service API**
immediately authenticates as that client. Change `clientId` and re-send to switch tenants.

If you set `jwt_token` some other way (curl + manual paste, step 4 above): open the **Local**
environment's editor (environment dropdown > the pencil/gear icon, or `Cmd+E` / `Ctrl+E`), paste
the token as the value of `"jwt_token"` in the JSON, save.

**Finding which client an event belongs to.** `POST /local/event-generator/generate` assigns each
synthetic event a random client (`CLIENT001`/`002`/`003`) and returns it in the response:

```json
{"eventId": "EVT013", "deliveryIds": ["003b1ff1-18fc-48fa-a0d2-f1441a66642b"], "clientId": "CLIENT003", "eventType": "credit_card_payment"}
```

Use that `clientId` when minting the token (step 4) — a token for the wrong client returns 404 for
that delivery's id (cross-tenant access, by design, never 403). There's no way to request a
specific client from the generator itself; it's a demo tool, not a fixture builder.

**Two ids, not one — don't put `eventId` in a self-service URL.** `eventId` (`EVT013`) is
the *platform event's* own id — `notification_events.event_id`, `text`, whatever format the
upstream producer assigned it (the case's sample data uses `EVT001`; the generator deliberately
mints something that looks nothing like a UUID, for the reason below). It is **not** what
`GET /notification_events/{notification_event_id}` or `.../replay` take, despite the path segment
being named `notification_event_id` — that name is the case's own wording (Task 2), but what it
addresses is a *delivery* (ADR-003 §3): one event can fan out to N deliveries, one per matching
subscription, and the delivery — not the raw event — is the addressable resource. Use a value from
`deliveryIds` in the URL, not `eventId`. `deliveryIds` is empty when no active/verified
subscription matched the event's `(client_id, event_type)` at ingest time — that event is stored
for audit but will never produce anything to query (no retroactive matching; re-seed subscriptions
*before* generating, not after, or the events land orphaned).

The generator's `eventId` is intentionally **not** UUID-shaped: `EVT%03d` off a monotonic counter
that starts at `011` (past the sample file's reserved `EVT001`-`EVT010`) and grows past 3 digits
naturally as the count does (`%03d` is a minimum width, not a cap — `EVT1000000` at a million, no
truncation), same shape as the sample data — specifically so it can't be mistaken for the
UUID-shaped `deliveryId` the self-service URLs actually expect. An earlier version of this tool
generated `"EVT-" + UUID.randomUUID()`, which looked enough like a delivery id to paste into the
wrong place. The counter resets to `011` on app restart; a regenerated id just idempotently no-ops
against an already-stored event (`ON CONFLICT DO NOTHING`, ADR-003 §2) rather than erroring — if
you want fresh deliveries rather than a no-op, re-seed subscriptions and generate again, don't
worry about the id repeating.

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
