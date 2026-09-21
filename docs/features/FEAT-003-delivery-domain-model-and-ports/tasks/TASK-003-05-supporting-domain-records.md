---
id: TASK-003-05
feature: FEAT-003
title: Supporting domain records (NotificationEvent, Subscription, DeliveryAttempt)
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-01]
date: 2026-09-20
---

# TASK-003-05: Supporting domain records

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The three remaining `domain/model` records listed in ADR-005 §1, plus the two small enums `Subscription` needs. Plain records, no behavior beyond validation.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/event/NotificationEvent.java` (new)
  - `src/main/java/com/cobre/challenge/domain/model/subscription/Subscription.java` (new, including nested or sibling `VerificationState` and `CircuitState` enums in the same file if the agent judges them one concern; otherwise this task may add those two enums as a third and fourth small file — they are trivial value lists)
  - `src/main/java/com/cobre/challenge/domain/model/delivery/DeliveryAttempt.java` (new)
- Concern: the remaining domain vocabulary.

`NotificationEvent` (ADR-003 §3): `eventId` (String, the platform id e.g. `EVT001`), `clientId`, `eventType`, `content`, `createdAt` (`Instant`). Immutable, never updated after insert.

`Subscription` (ADR-003 §3) — domain-relevant fields only: `subscriptionId` (UUID), `clientId`, `targetUrl` (String, **not** `java.net.URI` or `URL` — no resolution or network type in the domain), `secretRef`, `previousSecretRef` (`Optional`), `previousSecretExpiresAt` (`Optional<Instant>`), `eventTypes` (`Set<String>`, immutable copy), `active` (boolean), `verificationState`, `maxConcurrency` (int), `circuitState`, `throttledUntil` (`Optional<Instant>`).
- `VerificationState`: `PENDING_VERIFICATION, VERIFIED` (ADR-005 §3).
- `CircuitState`: `CLOSED, OPEN, HALF_OPEN` (ADR-006 §1.2).
- One derived accessor: `boolean isDeliverable()` = `active && verificationState == VERIFIED` (ADR-005 §3's exact rule). Nothing more.

`DeliveryAttempt` (ADR-003 §3): `deliveryId` (UUID), `attemptNumber` (int), `httpStatus` (`OptionalInt` — absent when no response was received), `responseTimeMs` (int), `responseExcerpt` (`Optional<String>`), `error` (`Optional<String>`), `attemptedAt` (`Instant`). No `outcome` column: ADR-003 §3 states the outcome is derived by the classifier, not stored, so this record must not carry an `AttemptOutcome` component.

## Out of Scope

- **No circuit-breaker transition logic on `Subscription`.** The four transitions are conditional SQL writes owned by the relay and worker (ADR-006 §1.2) and are not modeled as domain methods here.
- No `circuitOpenedAt`/`circuitBackoff`/`consecutiveOpens` on the domain record: those exist to drive the relay's due-query predicate in SQL (ADR-006 §2) and are persistence/adapter concerns, not domain behavior in this feature. If a later use case genuinely needs them in the domain, that is a change to this record in a later task, with a reason.
- No `createdAt`/`updatedAt` audit columns on `Subscription`.
- No URL validation, no SSRF allow-list check — that is a security-engineer concern in the outbound adapter (ADR-002's A01-SSRF row), not a domain record's constructor.
- No secret plaintext anywhere: `secretRef` is a reference, never a key (ADR-004 §3, A04).

## Acceptance Criteria

- [ ] All three types are immutable records; collection components are defensively copied to an immutable collection in the compact constructor.
- [ ] Optional-valued accessors return `Optional`/`OptionalInt`, never `null`.
- [ ] `Subscription.isDeliverable()` is exactly `active && verificationState == VERIFIED`.
- [ ] `DeliveryAttempt` has no `AttemptOutcome` component.
- [ ] No import outside `java.*` in any of the files.
- [ ] Tests written and passing: plain JUnit covering `isDeliverable()`'s truth table and the defensive-copy/`null`-rejection behavior of the compact constructors.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced — in particular no field holds a plaintext secret (A04).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
