---
id: TASK-008-27
feature: FEAT-008
title: ReplayIdempotencyGuard — early rejection of a double click
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-26]
date: 2026-09-21
---

# TASK-008-27: `ReplayIdempotencyGuard`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/ReplayIdempotencyGuard.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/config/IdempotencyProperties.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/NotificationEventController.java` (modified — the guard consulted around the replay handler)
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/ReplayIdempotencyGuardTest.java` (new)
- Concern: the HTTP-level half of replay idempotency.

## What it is, and what it is emphatically not

ADR-005 §1: the `Idempotency-Key` header exists for **"early HTTP-level rejection of a double
click, on top of that DB-level guard"**. Two words matter.

**"Early"** — it short-circuits a repeat before the use case, the database and the pipeline are
touched. That is the whole value: a double click costs one request, not two round trips.

**"On top of"** — it is **not** the correctness guarantee. `idx_deliveries_live_pair` is
(ADR-003 §2, ADR-005 §1, ADR-007 §6's closing note). If the guard's entry is evicted, expired, or
lost to a pod restart, the second replay still hits the partial unique index and still becomes a
409. **The guard failing open is acceptable; the index failing is not, and it cannot.** Write that
sentence into the class javadoc's one line so a later reader does not mistake this for the
control.

Consequently: **no database table, no `idempotency_keys` migration, no persistence of any kind.**
ADR-005 §1 does not ask for one, ADR-003 §3's schema does not contain one, and adding one would be
a schema change this feature has explicitly excluded.

## Shape

A bounded, in-process, TTL-expiring map keyed by **`(TenantId, deliveryId, idempotencyKey)`** —
all three. Keying on the header alone would let one client's key collide with another's, and
keying without the delivery id would make one key cover unrelated replays.

On a hit within the TTL, return the **same outcome** the first call produced (status and body), so
a double click is idempotent rather than being reported as a fresh conflict. Store only what is
needed to reproduce that response; do not cache a domain object.

`IdempotencyProperties` (`@ConfigurationProperties("challenge.self-service.idempotency")`) carries
the TTL (a short one — minutes, not hours; ADR-005 gives no number, so label the default as a
proposal in the same class as the other tuning values) and the maximum entry count.

**Bounded size with eviction is mandatory**, for the same reason as TASK-008-24's buckets: an
unbounded map keyed by client-supplied input is itself a denial-of-service vector.

**Per-pod, like the rate limiter.** With N instances a repeat can land on a pod that has never
seen the key, and then the DB-level guard does its job. Say so in the javadoc; do not reach for
Redis.

**Virtual-thread constraint:** shared mutable state on the request path. **No `synchronized`
block may enclose I/O** — and the natural mistake here is holding a lock across the use-case call
while "reserving" the key. Do not do that. Reserve and release with atomic operations, or accept a
narrow race that the database guard closes anyway (it does). State which you chose and why it
cannot pin a virtual thread.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Fully unit-testable with an injected `Clock` or time supplier — inject one; do not sleep.

`DEFERRED — Testcontainers`: **named test 5 — two rapid replays create one delivery** — which is
owned by TASK-008-28 and which proves the *database* guarantee, not this guard's. A unit test
showing the guard returns a cached response on the second call is **not** named test 5 and must
not be labelled as it.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- Any database table, migration or persistence.
- Any distributed cache or new dependency.
- Rate limiting — TASK-008-24; volume and correctness are different jobs and neither substitutes
  for the other.
- Applying the guard to the two GET endpoints. They are safe and idempotent already; adding it
  there is pure overhead.
- The use case's DB-level conflict handling — TASK-008-19.

## Acceptance Criteria

- [ ] The key is `(TenantId, deliveryId, idempotencyKey)` — all three components.
- [ ] A repeat within the TTL returns the first call's outcome without invoking the use case,
      asserted by a mock that records zero further calls.
- [ ] After the TTL expires, the same key reaches the use case again.
- [ ] The map is bounded with eviction and a configurable maximum.
- [ ] No table, migration or persistent store is introduced.
- [ ] No new dependency is added.
- [ ] No `synchronized` block encloses the use-case call or any I/O; the handover states why the
      approach cannot pin a virtual thread.
- [ ] The class javadoc is one line and says the DB-level partial unique index remains the
      authority.
- [ ] The guard is applied **only** to the replay endpoint.
- [ ] Time is injected; no test sleeps.
- [ ] Unit tests cover: first call passes through; immediate repeat returns the cached outcome and
      does not call the use case; a different key for the same delivery passes through; the same
      key for a different tenant passes through; the same key for a different delivery passes
      through; expiry; eviction under the size bound.
- [ ] **DEFERRED — Testcontainers:** named test 5, owned by TASK-008-28. This task's tests must
      not claim to cover it.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A06, A10: failing open here is safe by
      design, and the reason is documented).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
