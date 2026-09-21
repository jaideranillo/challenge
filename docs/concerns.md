# Concerns

Deviations from ADR text, recorded per CLAUDE.md's "Design authority" rule
(implement as specified, log disagreement here rather than reopening the ADR).

## FEAT-003's committed `Delivery` record and three port files change after `Ready for Review`

The 2026-09-20 Tech Lead directive changes files that FEAT-003 already delivered
and that TASK-003-12 / TASK-003-04 still carry as `Ready for Review`. Flagged
rather than done quietly, because the blast radius is wider than the two port
files the directive names:

| File | Change | Owner |
| --- | --- | --- |
| `domain/model/delivery/Delivery.java` | two new components (`eventCreatedAt`, `traceContext`), canonical constructor and all four transition methods, javadoc corrected | TASK-004-02 |
| `.../port/out/persistence/DeliveryRepositoryPort.java` | deleted, replaced by two interfaces | TASK-004-03 |
| `.../port/out/persistence/SubscriptionRepositoryPort.java` | `transitionCircuitState` replaced by four operations | TASK-004-04 |
| `src/test/.../domain/model/delivery/DeliveryTest.java` | every `Delivery` construction site | TASK-004-02 |
| `src/test/.../port/out/persistence/PersistencePortsTest.java` | asserts the old port shape | TASK-004-03, -04 |

The FEAT-003 task files are **not** retro-edited and their `Ready for Review`
status is left alone: they were an accurate record of what was built at the time,
and rewriting history to match a later directive loses the fact that the contract
moved. The superseding work is FEAT-004's, and the ADR amendments (A1-A4, B1-B3,
C1-C2, D1-D2, E1) are the durable record of why.

Whether the two new `Delivery` components belong in the domain record at all is
argued in ADR-003 Amendment A3 and in the withdrawn parenthetical further down
this file. Short version: a field belongs in the aggregate when a use case reads
or writes it, which both of these do and which `created_at`/`updated_at` do not.

## The port's clock source is inconsistent: most writes take an `Instant`, three use the database clock

FEAT-003 scope says "every method that needs time takes an `Instant` parameter"
(no `ClockPort`). Three operations do not:

- `SubscriptionRepositoryPort.deactivate(UUID)` (pre-existing) — no `Instant`
  parameter at all.
- `DeliveryPipelineRepositoryPort.deferDelivery(UUID, Instant nextAttemptAt)` —
  its one `Instant` parameter is a target time, not "now"; the directive fixed
  its arity at two parameters.
- `SubscriptionRepositoryPort.setThrottledUntil(UUID, Instant throttledUntil)`
  (TASK-004-18) — it does take an `Instant`, but that `Instant` is the throttle
  deadline (a future point in time), not the time of the write. Binding it to
  `updated_at` would let the audit column move backward: this write is
  deliberately unconditional (ADR-004 §1 — later `Retry-After` must win even
  against an earlier call), so a later call with an earlier `until` still
  updates the row, and setting `updated_at` to that earlier `until` would regress
  the audit column below what a previous call already wrote.

All three write `updated_at` via PostgreSQL's `now()` instead.

Implemented as directed / decided. The consequence is small but real and worth
stating: a row's `updated_at` can come from either the application or the
database clock depending on which operation touched it last, and ADR-002 §2.1's
60s staleness reclaim compares `updated_at` against the relay's clock. The two
clocks are NTP-synced in any realistic deployment and the threshold has roughly
6.5x margin (ADR-002 §2.1), so this does not threaten the reclaim. It does make
a test that asserts an exact `updated_at` value unreliable for these three
operations, which is why TASK-004-10 and TASK-004-18 assert a bound rather than
an equality there. Adding a "now" parameter to all three would make the port
uniform; not done for `deactivate`/`deferDelivery` because the directive
specified their arity explicitly, and not done for `setThrottledUntil` because
widening its signature is out of TASK-004-18's scope and no caller needs a
separate "now" today.

## ADR-005 §1 lists `RetryPolicy` under `domain/model`; implementation places it under `domain.policy`

`RetryPolicy`, `ResponseClassifier`, `AttemptOutcome` and `TransportFailure` are
stateless decision rules over the domain aggregates, not aggregate state
themselves, so FEAT-003 groups them in `domain.policy` rather than
`domain.model` (software-architect review, 2026-09-20). No behavior differs
from ADR-005 §1's intent — this is a package-naming refinement only.

## Domain package layout further split by aggregate scope, and by kind within each aggregate

Post-implementation, at the user's explicit request, `domain.model` and
`application.port.*` were reorganized twice:

1. Package-by-feature: `domain.model.delivery` / `.event` / `.subscription`,
   `application.port.in.pipeline` / `.selfservice`, `application.port.out.persistence`
   / `.queue` / `.webhook` (software-architect taxonomy, 2026-09-20).
2. Within each aggregate, enums and the aggregate's own exception were further
   split into `<aggregate>.enums` and `<aggregate>.exception` subpackages
   (e.g. `domain.model.delivery.enums.DeliveryStatus`,
   `domain.model.delivery.exception.IllegalDeliveryTransitionException`) — a
   user decision made after being shown the package-by-kind tradeoff (fragments
   a single concept across packages, no cohesion gain). Recorded here as a
   deviation from typical hexagonal package-by-feature guidance, not reopened
   further since it is a working-tree organizational choice, not an ADR
   decision.

## ADR-007 §5.1/§5.2's `TenantId` type is not introduced by FEAT-004; adapters bind `String clientId`

ADR-007 §5.1 mandates `TenantId` as a framework-free `domain/model` record whose
only production construction site is `AuthenticatedTenantResolver`, and §5.2
sketches port shapes (`findByIdForTenant(TenantId, DeliveryId)`,
`findByTenant(TenantId, DeliveryQuery)`) accordingly. The three `port/out`
persistence interfaces written under TASK-003-12 instead take `String clientId`.
FEAT-004 (the JDBC adapters) implements the ports **as they exist** and does not
introduce `TenantId`.

Reasoning, recorded rather than reopened (software-architect, 2026-09-20):

1. ADR-007's own `## Downstream` section assigns "the tenant-scoped port
   signature changes and their adapters" to **ADR-007's** feature breakdown, not
   to a prior persistence feature. Introducing `TenantId` in FEAT-004 would be
   FEAT-004 doing ADR-007's work out of order.
2. The load-bearing half of §5.1 is not the type, it is the *single production
   construction site* plus the three ArchUnit rules. Both require
   `adapter/in/web/security` and a Spring Security resource-server
   configuration, neither of which exists. A `TenantId` record shipped without
   them is constructible by any class from any string, which is the same
   guarantee `String` gives while *looking* like ADR-007's control is in place.
   That is strictly worse than `String`, for the same reason ADR-007 §5.4 says a
   test running as the table owner "passes for the wrong reason".
3. The behavioural property is available today at zero cost and FEAT-004 takes
   it: both client-facing reads bind `client_id` as a named parameter, and each
   has a Testcontainers test asserting tenant B cannot read tenant A's row.
4. The later change is mechanical and confined: `String clientId` becomes
   `TenantId tenant`, the bind site becomes `tenant.value()`, and no SQL text
   changes.

## RESOLVED (2026-09-20): ADR-007 §5.2's interface-segregation split is not applied to `DeliveryRepositoryPort`

ADR-007 §5.2 states that client-facing and internal-pipeline ports are
"separate interfaces ... the split is not stylistic, it is the boundary between
'acts for one tenant' and 'acts for the platform', and it is visible in the type
system." `DeliveryRepositoryPort` as written under TASK-003-12 spans both: three
cross-tenant pipeline methods (`insert`, `transitionStatus`, `claimDue`) and two
tenant-scoped client-facing reads (`findById`, `findPage`) on one interface.

**Resolved by Tech Lead directive, 2026-09-20.** The split is done, not deferred:
`DeliveryPipelineRepositoryPort` (cross-tenant) and `DeliveryQueryRepositoryPort`
(tenant mandatory). Recorded as ADR-007 Amendment E1 and ADR-003 Amendment A2,
delivered by FEAT-004's revised breakdown. The `TenantId` entry above is
**not** resolved with it: the split is about which interface a method lives on,
`TenantId` is about the parameter's type, and only the former is in FEAT-004.

## ADR-007 §5.3 (second half) and §5.4 are absent after FEAT-004

Of ADR-007's four tenant-isolation layers, FEAT-004 delivers only layer 3a (the
bound `AND client_id = :client_id` predicate). Layer 1 (`TenantId` with one
construction site), layer 3b (`SET LOCAL app.client_id` via a
`TenantSessionBinder`) and layer 4 (PostgreSQL RLS policies under a
non-bypassing `challenge_api` role, plus the `challenge_pipeline` role, two
connection pools, and a dual-role Testcontainers fixture) are all deferred to
ADR-007's feature breakdown, which §Downstream already names as their owner.

Consequence stated plainly: between FEAT-004 and that feature, tenant isolation
on `findById`/`findPage` rests on layer 2 (the mandatory port parameter) and
layer 3a alone. Neither of FEAT-004's two correctness-critical queries is
affected, since both are pipeline-side and cross-tenant by design (ADR-007 §5.2,
ADR-002 §2.1), so RLS would not gate them under `challenge_pipeline` either way.

## RESOLVED (2026-09-20): `transitionStatus` and `transitionCircuitState` cannot express the companion columns their ADRs require

Two `port/out` signatures from TASK-003-12 are narrower than the writes their
ADRs specify, and FEAT-004's adapters are therefore scoped to exactly what the
signatures express:

- `DeliveryRepositoryPort.transitionStatus(UUID, DeliveryStatus expected, DeliveryStatus target, Instant now)`
  can write `status` and `updated_at` only. ADR-003 §1.1's write table also
  requires `attempt_count`, `next_attempt_at`, `last_error` and `delivered_at`
  to move on the `RETRYING`/`DEAD`/`DELIVERED` transitions, and there is no port
  method that writes them (TASK-003-12 deliberately forbade a blanket upsert, for
  a good reason). ADR-006 §1.1 / ADR-002 §2.2 step 3's bulkhead deferral write
  (`next_attempt_at = now() + 10-20s jittered WHERE status = 'QUEUED'`) is
  likewise inexpressible.
- `SubscriptionRepositoryPort.transitionCircuitState(UUID, CircuitState expected, CircuitState target, Instant now)`
  can write `circuit_state` and `updated_at` only. ADR-006 §1.2 requires
  `circuit_opened_at`, `circuit_backoff` and `consecutive_opens` to be written in
  the same statement as a trip.

**Resolved by Tech Lead directive, 2026-09-20.** Both generic methods are replaced
by intent-revealing operations that each write their companion columns in one
atomic conditional `UPDATE`: `claimForProcessing`, `markDelivered`,
`scheduleRetry`, `markDead`, `markFailed`, `deferDelivery` on the delivery
pipeline port, and `tripCircuit`, `reopenCircuit`, `promoteToHalfOpen`,
`closeCircuit` on `SubscriptionRepositoryPort`. Recorded as ADR-003 Amendment A1
and ADR-006 Amendments B1-B3. The bulkhead-deferral write that was also flagged
as inexpressible is now `deferDelivery`.

## RESOLVED (2026-09-20): No port method reads or writes `deliveries.trace_context`

ADR-003 §3 creates the column and ADR-002 §3.1 requires the consumer to restore
the persisted `traceparent` from it when the SQS message attribute is absent.
No method on `DeliveryRepositoryPort` wrote or returned it, and `Delivery` did not
model it.

**Resolved by Tech Lead directive, 2026-09-20.** `Delivery` gains
`Optional<String> traceContext`, the insert writes the column and the pipeline
port's `findById` returns it, so no extra port parameter is needed. Recorded as
ADR-003 Amendment A3.

The parenthetical above ("correctly ... a persistence-only concern") was the
wrong call and is withdrawn. `created_at` and `updated_at` are audit metadata no
use case reads; `trace_context` is written by the ingest use case and read by the
worker use case (ADR-002 §3.1's fallback path). Being read and written by the
application layer is the test for aggregate membership, and this column passes it
while the other two do not. `created_at`/`updated_at` stay out of the record.

## TASK-004-01: V4's NOT NULL constraint requires updating two existing schema tests

TASK-004-01's acceptance criterion states "FEAT-002's `DeliveryDueQueryIndexTest` and
`DeliveryIdempotencyIndexTest` must still pass unmodified." V4 adds
`event_created_at NOT NULL` with no `DEFAULT`, so any `INSERT` into `deliveries`
that does not supply the column fails with a not-null violation. Both existing tests
use helper INSERT SQL that does not include `event_created_at`.

Implemented as specified (NOT NULL, no DEFAULT). The two test files were updated
minimally: only the INSERT SQL was extended to include `event_created_at = now()` (or
`now() - interval '5 minutes'` to match the seed data's age). The test assertions and
their intent are unchanged. "Unmodified" is read as "the assertions must still pass"
rather than "the file bytes must not change", because the latter is impossible given
the NOT NULL constraint.

## ADR-002 §2.1's 5-minute `next_attempt_at` push has no port parameter

`claimDue(int batchLimit, Instant asOf)` carries no interval, so the "push
`next_attempt_at` forward by 5 minutes" half of ADR-002 §2.1's claim lives as a
named constant in `DeliveryPipelineJdbcRepository` (TASK-004-11). A parameter would be
the cleaner shape, since the value is a dispatch policy rather than a storage
detail. Not added here: widening the port is `backend-engineer`'s file and no
caller needs it configurable yet.

## RESOLVED (2026-09-20): ADR-003 §3 names `notification_events.created_at` as the list endpoint's filter column, but its own index list and ADR-005 §1 put it on `deliveries`

ADR-003 §3's `notification_events` paragraph says "`created_at` is the
event-creation timestamp the API's date-range filter runs against". The same
section's index list instead creates `(client_id, created_at)` **on
`deliveries`** "for the list endpoint's default ordering and date-range filter,
keyset-paginated", which is what FEAT-002's `V2` migration built
(`idx_deliveries_client_created_at`), and ADR-005 §1 specifies keyset pagination
"on `(created_at, id)`" where `id` is the delivery id. Those are two different
columns, and for a `REPLAY`-origin row they differ by design.

FEAT-004's first pass chose **`deliveries.created_at`**, because that is the
column the committed index served and because a keyset must order on the same
table as its tiebreak to be index-servable.

**Resolved by Tech Lead directive, 2026-09-20, the other way, and the first pass
was wrong.** `event_created_at` is denormalized onto `deliveries`, copied at
insert and never updated; the filter and the keyset move onto it and the index
becomes `(client_id, event_created_at)`. Recorded as ADR-003 Amendment A4 and
ADR-005 Amendment D2, delivered by TASK-004-01 (migration) and TASK-004-14
(`findPage`), with the replay-shaped fixture asserted in TASK-004-15.

Denormalization gets both properties the first pass treated as a trade, and the
decisive argument was one the first pass missed: ADR-005 §1 always read "filters
by **event creation date range**", and for a `REPLAY` or `RECOVERED` row
`deliveries.created_at` is when the replay was requested, not when the event
happened. Keying the filter on it would file a replayed delivery under the wrong
day and hide it from a client querying the event's actual window. The value had
to be the event's, and it had to be on `deliveries`.

## Port command/result records extracted from nested to `dto` subpackages

At the user's explicit request, every use-case/port interface's nested
`record`/`enum` (Command, Result, DTO types previously declared inside the
interface file, e.g. `RegisterNotificationEventUseCase.RegisterNotificationEventCommand`)
was extracted to its own top-level file under a `dto` subpackage of that
port's package (`application.port.in.pipeline.dto`,
`application.port.in.selfservice.dto`, `application.port.out.persistence.dto`,
`application.port.out.queue.dto`, `application.port.out.webhook.dto`),
following the same package-by-kind convention already applied to
`domain.model`. `ReplayDeliveryResult` and its `Accepted`/`Rejected`
implementations were kept as their own top-level files directly in
`application.port.in.selfservice` rather than under `dto`, since they are a
behavioral result hierarchy, not plain data-transfer records. TASK-003-10/11/12/13
updated to reflect the current file layout (2026-09-20).

## The ingest endpoint ships unauthenticated (FEAT-005, open)

At the Tech Lead's explicit direction of 2026-09-20, producer-to-gateway
authentication was cut from FEAT-005 after the breakdown was written. The
`security-engineer` task that carried it was removed rather than deferred within
the feature, and no task in FEAT-005 is assigned to that role. Implementing as
specified, per the "Design authority" rule in CLAUDE.md; logging it here because
it is an open exposure rather than a design disagreement.

What ships: `POST /internal/events` is reachable without a credential. An
unauthenticated caller can write arbitrary `notification_events` and
`deliveries` rows for any `client_id`. The tenant model of that endpoint —
the caller chooses `client_id` in the request body — is correct only on the
premise that the caller is an authenticated internal producer (ADR-002 §1.1:
"the caller is a platform-internal service, not a client"). Without
authentication that premise does not hold, so A01 and A07 are one open
exposure, not two independent ones.

Why it matters more later than it does today: FEAT-005 makes no outbound call.
Once the worker lands, a row written through this endpoint becomes an HTTPS POST
of attacker-chosen content to a client's registered webhook URL, signed by the
platform. The exposure grows from "junk rows in two tables" to "the platform
delivers attacker payloads to clients under its own signature."

Not changed by the cut: ADR-002 §1.1 step 1 and Q10 remain `Accepted` — IAM /
SigV4 is still the decision, and Q10's "edge vs. application" question stays
open rather than being resolved by TASK-005-16 as originally planned. No ADR
amendment was made, because a sequencing decision is not a reversal.

Two follow-ups this leaves owner-less, both needing a `security-engineer` task
in a later feature: the authentication itself, before any deployment reachable
by untrusted traffic; and a review of the `software.amazon.awssdk:sqs` promotion
to a runtime dependency (A03), which now lands with only TASK-005-01's own
acceptance criteria behind it.

**Update 2026-09-21 (Tech Lead call): not blocking FEAT-007.** The events
currently reaching this endpoint are mock-generated, so the practical exposure
is low for now and no FEAT-007 task waits on it. The entry stays open and
unresolved; the deprioritization is a sequencing decision, not a closure.

**Update 2026-09-21 (scope correction): this is not an SSRF problem.** An
earlier draft of the FEAT-007 entry below combined the two. They are distinct
and are tracked separately from here on. The webhook destination comes from the
`subscriptions` row, never from the inbound event, so an unauthenticated caller
cannot choose where the platform connects. What it *can* do is cause deliveries
to fire at **existing, legitimate subscription targets**, with content it chose,
signed by the platform. That is amplification and abuse of the delivery pipeline
against the platform's own clients — a real problem with a different shape, a
different fix (authenticate the producer) and a different blast radius from
SSRF, whose fix is outbound URL validation. Merging them would have hidden the
fact that closing one closes nothing of the other.

## FEAT-007 fixes four values the ADRs leave explicitly open

Recorded during the FEAT-007 (delivery worker) breakdown, 2026-09-21. None of
these is a contradiction in an ADR; each is a place an `Accepted` ADR says the
value or the mechanism is a follow-up, and the worker cannot ship without one.
Each is fixed to the smallest concrete choice, bound to a configuration key, and
is listed in `docs/features/FEAT-007-delivery-worker/feature.md`'s "Ambiguities
resolved by inference" table. **No ADR was amended.**

1. **Signing scheme (ADR-004 §2: "digest algorithm, canonicalization, header
   encoding ... remains a follow-up").** FEAT-007 uses HMAC-SHA256 over
   `<X-Cobre-Timestamp value> + "." + <body UTF-8>`, lowercase hex, in
   `X-Cobre-Signature`, with `X-Cobre-Signature-Previous` during a rotation
   window. The two-header shape is ADR-004 §2's own stated preference; the
   algorithm, separator and encoding are this feature's choice and are the part
   a later signing-scheme decision may overrule. Changing them is a client-facing
   breaking change once anyone integrates, which is the reason for recording it
   here rather than leaving it in code.
2. **Secret resolution (ADR-004 §3: `secret_ref` is "a secrets-manager key").**
   No secrets manager exists in this project and no ADR designs one. FEAT-007
   adds `WebhookSecretPort` with a configuration-backed resolver
   (`challenge.webhook.secrets.<ref>`). The ADR's invariant is preserved — the
   database still holds a reference, never plaintext — but the reference now
   resolves against configuration, which is weaker than a managed secret store
   in rotation, audit and blast radius. The port is the seam: replacing it is a
   one-adapter change.
3. **Circuit-breaker numbers (ADR-006 Q7: "proposals, not derived numbers").**
   **Profile-specific**, per Tech Lead direction of 2026-09-21. Default profile
   (production): threshold 10, base cooldown 30s, cap 1h, giving 30s -> 1m ->
   2m -> ... -> 1h. `local` profile (demo): threshold 3, base 10s, cap 60s,
   giving 10s -> 20s -> 40s -> 60s. The escalation is not a separate setting; it
   is ADR-006 §1.2's `base * 2^consecutive_opens` capped at the max, with
   different inputs. The threshold's production value is ADR-006 §1.2's own
   proposal; every other number here is FEAT-007's, and all of them are
   configuration keys. The local values sit with the compressed retry backoff
   and relay poll interval already in `application-local.yaml`, under that
   file's existing warning that local timings are not the real ones.
4. **What makes a `Retry-After` usable (ADR-004 §1's "when present and sane").**
   A parseable positive value above the profile's ceiling is **clamped to the
   ceiling, not rejected** — 1h in production, 60s locally, deliberately equal
   to the breaker's `max-cooldown` so a client cannot park a subscription for
   longer than the breaker's own worst case. Zero, negative, a past date and
   unparseable text all fall back to the normal backoff schedule, which is a
   normal outcome and never an error. **Both RFC 7231 forms are accepted**,
   delta-seconds and HTTP-date: a date-form value is not illegible merely for
   being non-numeric, and treating it as garbage would silently downgrade every
   client that uses it.

## FEAT-007 closes SSRF at the application level; only the network-level half defers

ADR-002's A01 row names the exposure ("the webhook URL is client-supplied and
the service calls it from inside the platform network") and defers the design;
ADR-007 §Downstream calls it "its own follow-up". **Tech Lead direction of
2026-09-21 splits that row in two, and only one half defers:**

**(a) Network-level egress controls — still deferred.** Security groups, egress
restriction, a controlled NAT or proxy path. Infrastructure, not code, and not
applicable to a local run. Unchanged, unowned, and still needing its own ADR or
infrastructure work.

**(b) Application-level SSRF validation — implemented in FEAT-007, not
deferred.** `OutboundUrlValidator` (TASK-007-19), wired into the outbound
adapter before any socket opens (TASK-007-20): HTTPS only; DNS resolved and
every resulting address checked; loopback, any-local, private, link-local
(including `169.254.169.254`), multicast and IPv4-mapped forms rejected; DNS
failure fails closed; redirects never followed. Validation runs **on every
attempt**, never once at subscription creation, which is the DNS-rebinding
defense.

Two things are recorded here rather than left in code:

1. **The local-profile allowlist is a deliberate, narrow exception, and as of
   2026-09-21 it waives the HTTPS rule as well.** The demo's stub receiver runs
   on `localhost` over plain HTTP, and nothing in this project configures
   `server.ssl`. The allowlist (`challenge.egress.allowed-hosts`) alone waived
   only the private-range check, so a local attempt still died on the scheme:
   the exception existed and did nothing. Rather than terminate TLS in front of
   the stub — which needs either a committed private key or a profile-specific
   `SSLContext` on the webhook client, i.e. relaxing the very TLS path ADR-004
   §2 protects, plus certificate expiry as a recurring way for the demo to
   break — the scheme rule itself is made waivable, in the one class whose job
   is to make that decision.

   The waiver requires **two conditions**: the host must be in
   `challenge.egress.allowed-hosts`, **and** the active Spring profile must be
   `local`. Every other profile keeps HTTPS mandatory with no exceptions. It
   never applies to a host that is not on the list, so it cannot become a
   blanket "plaintext is fine", and an allowlist entry that somehow appeared in
   a production configuration would waive nothing on its own.

   An earlier draft of this decision used a second boolean property plus a
   fail-fast startup guard instead of the profile check. The Tech Lead chose the
   profile, and it is the better condition: a profile is a first-class
   deployment concept rather than one more YAML line, it cannot be set by
   editing `application.yaml`, and it needs no guard bean to police a flag that
   no longer exists. Neither that property nor that guard should be
   reintroduced.

   The unit test that rejects `localhost`, the private ranges and
   `169.254.169.254` under the default profile is the guard that this waiver
   never reaches production. If it is ever deleted or weakened, this entry is
   the record of why it existed.
2. **RESOLVED (2026-09-21): the ADR-004 §1 classification divergence is closed,
   with no contract change.** *History, kept deliberately:* an earlier draft of
   this feature routed a validator rejection through the adapter as a transport
   failure, which `ResponseClassifier` maps to `RETRYABLE`, so a blocked URL
   would have retried instead of dying immediately as ADR-004 §1 requires. That
   draft concluded no `AttemptOutcome` value could express it and logged a
   standing deviation. **That conclusion was wrong.** `NON_RETRYABLE` already
   exists, already terminates in `DEAD`, and already returns `false` from
   `countsTowardCircuitBreaker()` — which is exactly the required semantics.

   The resolution, per Tech Lead direction:
   - The validator returns a three-state verdict, and the **use case** — not the
     adapter — classifies it, because the use case is where classification
     already lives and `WebhookResponse` cannot express a permanent failure.
   - `POLICY_REJECTED` (resolved cleanly to a private/reserved range, the
     metadata IP, or a non-HTTPS scheme) -> `NON_RETRYABLE` -> `DEAD`. Retrying
     changes nothing, and it indicates invalid configuration rather than an
     unhealthy client endpoint.
   - `DNS_FAILURE` (timeout, NXDOMAIN, resolver error) -> `RETRYABLE`, and it
     **does** count toward the breaker, exactly as ADR-004 §1 already classifies
     a DNS failure. The two are deliberately not collapsed.
   - A policy rejection does **not** count toward the breaker. The breaker
     measures the health of a resolved endpoint; a rejected target was never
     contacted.
   - The attempt is recorded the way ADR-003 §3 already models a pre-HTTP
     failure: `error` set, `http_status` absent. No new column.
   - A policy rejection emits a security signal — the untagged counter
     `notification.webhook.egress.rejected` and a warn log with `delivery_id`,
     `subscription_id`, host and resolved address — because a target resolving
     into a private or metadata range is possible SSRF or DNS rebinding, not a
     routine dead delivery.

   **No `AttemptOutcome` value, no `TransportFailure` value, no enum, no column
   and no port signature changed.** Delivered by TASK-007-19 and TASK-007-20,
   verified in TASK-007-21. Nothing in ADR-004 §1 is now diverged from.

Note what this entry does **not** claim. It is a separate problem from the
unauthenticated ingest endpoint recorded above, and the two must not be merged:
the destination URL always comes from the `subscriptions` row, so an
unauthenticated caller cannot steer the outbound connection. That caller's
leverage is amplification against existing subscription targets, which is
tracked in its own entry with its own fix.
