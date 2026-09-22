---
id: TASK-008-24
feature: FEAT-008
title: Per-client rate limit filter — read budget vs replay budget
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-21]
date: 2026-09-21
---

# TASK-008-24: Per-Client Rate Limit Filter

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/ClientRateLimitFilter.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/TokenBucket.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/config/RateLimitProperties.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/ClientRateLimitFilterTest.java` (new)
- Concern: per-client, per-scope budgets. Three small production files, one concern, per the
  repo's package-by-kind convention.

## Placement — after authentication, never before (ADR-007 §6)

The filter sits **after** `BearerTokenAuthenticationFilter` (TASK-008-21, chain 3 step 7). The
budget is keyed on the verified `client_id` claim. Keying on anything unauthenticated would let
an attacker choose their own bucket.

This layer's job is **not** volumetric abuse: per-IP floods, malformed-request storms and
unauthenticated traffic belong at the edge gateway, which is out of this service's code and is
the only layer that can drop traffic before it costs a thread, a JWT verification and a database
connection. Do not try to cover that here.

## The budgets (ADR-007 §6 — labelled proposals, not measured)

| Bucket | Limit | Key |
|---|---|---|
| Read | 600 requests per minute, burst 60 | `(client_id, READ)` |
| Replay | 10 per minute **and** 200 per day, burst 5 | `(client_id, REPLAY)` |
| Unauthenticated | not budgeted in-process | rejected at the filter chain; the edge's problem |

Bind them to `RateLimitProperties` (`@ConfigurationProperties("challenge.security.rate-limit")`)
with those defaults and a comment marking them proposals, in the same class as ADR-004's Q5 and
ADR-006's Q7.

Replay is roughly two orders of magnitude tighter **deliberately**: it is a write that generates
outbound traffic to the client's own endpoint, and it is the one endpoint where an unbounded
client can turn this platform into a load generator aimed at themselves.

## Implementation shape and its honest limitation

**Hand-rolled in-process token buckets. No Bucket4j, no Redis, no database round trip.** ADR-007
§6's A03 note leaves this open and FEAT-008 takes the no-dependency option; adding a library here
is a defect (TASK-008-01 deliberately did not add one).

- Keyed by `(client_id, bucket)`, with **bounded-size eviction**. An unbounded map keyed by a
  claim is itself a denial-of-service vector — the tenant count is not known to this service. Size
  the bound from configuration and evict least-recently-used.
- Buckets are **per pod**. With N instances a client's effective budget is N times the configured
  number. This is the same bounded over-count ADR-006 §1.2 already accepts for the circuit
  breaker's pre-trip window, accepted here for the same reason: the alternative is new
  infrastructure and new latency on the hot path to make an approximate control exact. Say so in
  a one-line class javadoc so nobody later "fixes" it with Redis on their own initiative.

**Virtual-thread constraint (this is the pinning risk in this feature):** the buckets are shared
mutable state on the request path. **Do not hold a `synchronized` block across any I/O.** Prefer
atomic compare-and-set on a small immutable bucket state, or a lock held only around pure
arithmetic with no call out of it. A `synchronized` region that encloses a call to anything that
can block pins the carrier thread, which is the one thing this service's concurrency model cannot
afford. State in the handover which approach you took and why it cannot pin.

## Behavior on exhaustion (ADR-007 §6)

**429** with a `Retry-After` header and an RFC 9457 problem-detail body (reuse the shape
TASK-008-23 establishes). The request never reaches a use case, a database connection or the
pipeline. **A 429 from this service is not recorded as a delivery event of any kind** — do not
write anything to `deliveries` or `delivery_attempts` here.

Log rate-limit rejections structurally with `client_id` and bucket (A09); ADR-007 §9 names
rate-limit exhaustion by client as worth alerting on.

## What this is not

`Idempotency-Key` and the partial unique index are about **correctness**; rate limiting is about
**volume**. Both are needed and neither substitutes for the other (ADR-007 §6, closing note). Do
not implement any idempotency behavior here — that is TASK-008-27.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Fully unit-testable if the bucket takes an injected `Clock` or time supplier — do that, and never
call `System.nanoTime()`/`Instant.now()` directly, or the refill tests become sleep-based and
flaky.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- Edge / gateway limiting.
- Any distributed or shared counter.
- Any new dependency.
- The idempotency guard — TASK-008-27.
- Changing chain order — TASK-008-21 already placed the slot.

## Acceptance Criteria

- [ ] The filter runs **after** authentication and keys on the verified `client_id` claim; it
      never keys on an IP, a header or any unauthenticated value.
- [ ] Two budgets: read and replay, with the ADR's defaults, bound to configuration properties and
      labelled as proposals.
- [ ] Replay enforces **both** the per-minute and the per-day limit.
- [ ] The key map is bounded with eviction; a configurable maximum exists and is enforced.
- [ ] No new dependency is added.
- [ ] No `synchronized` block encloses any I/O; the handover states why the chosen approach cannot
      pin a virtual thread.
- [ ] Exhaustion returns 429 with `Retry-After` and an RFC 9457 body, and the request reaches no
      use case and no database.
- [ ] Nothing is written to `deliveries` or `delivery_attempts` on a 429.
- [ ] Rejections are logged with `client_id` and bucket; no token is logged.
- [ ] Time is injected; no test sleeps.
- [ ] Unit tests cover: a read under budget passes; the read budget exhausts at the limit; burst
      allowance behaves as configured; refill after the window; the replay minute limit; the
      replay **day** limit independently of the minute limit; the two buckets are independent for
      one client; two clients are independent; eviction bounds the map.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A03: no dependency added; A07).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
