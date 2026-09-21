---
id: TASK-006-12
feature: FEAT-006
title: Security review of the relay slice
status: Ready for Review
agent: security-engineer
depends_on: [TASK-006-09, TASK-006-10, TASK-006-11]
date: 2026-09-21
---

# TASK-006-12: Security review of the relay slice

## Feature

FEAT-006

## Assigned Agent

`security-engineer` — review, with fixes only where a finding is a one-line correction inside the
relay's own files. A finding that needs real redesign is reported, not silently fixed.

## Scope

- File(s) reviewed (not all necessarily modified):
  - `adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (`claimDue` only)
  - `application/usecase/RelayBatchClaimer.java`,
    `application/usecase/DispatchPendingDeliveriesUseCaseImpl.java`
  - `adapter/in/scheduling/DeliveryRelayScheduler.java`,
    `adapter/out/messaging/SqsNotificationQueueAdapter.java`
- Concern: the four exposures FEAT-006's Security Impact table names, verified against what was
  actually built.

## Scope — what to check

**A05 Injection.** `claimDue` is now the largest hand-written statement in the codebase and gained
a `CASE` expression and a correlated count. Verify every value is bound through
`MapSqlParameterSource` and that no predicate, interval, cap, status or enum literal is built by
concatenating a caller-supplied value. The existing `CLAIM_DUE_PUSH_INTERVAL` constant is a
compile-time literal and is fine; a new one derived from configuration would not be.

**A10 Mishandling of Exceptional Conditions.** Three paths, three required behaviours:
- a throwing claim propagates and the cycle is a no-op — nothing marked `QUEUED`, nothing
  enqueued (fail-closed);
- a partially failed publish performs **no** database write and does not propagate;
- the scheduler catches `Throwable` so one bad cycle cannot cancel the scheduled task.
  A relay that has silently stopped is the worst failure mode in this system (ADR-002 §2.1: the
  relay is the only mechanism that must work for delivery to happen at all). Verify this by
  reading the code, not by trusting the task file.

**A09 Logging & Alerting Failures.** No `notification_events.content`, no response body, no target
URL, no signature header, and no secret reaches a log line, an MDC entry, or a span attribute from
any relay class (ADR-002 §3.1). Verify the four counters exist and that **none** carries a
`client_id` or `subscription_id` tag (ADR-002 Q8 and §3.1 — this is an absolute prohibition, not a
preference). Verify an idle cycle does not log at info; the relay runs every 5 seconds.

**A01 Broken Access Control.**
- No relay class injects `DeliveryQueryRepositoryPort`. The cross-tenant reads are the ADR-007
  §5.2 carve-out for pipeline ports, and the port split is what keeps that carve-out narrow
  (ADR-007 Amendment E1).
- No HTTP surface, actuator endpoint, or externally reachable manual trigger was added for the
  relay.
- The per-subscription in-flight cap is enforced in SQL at claim time, never in Java after rows
  have already moved to `QUEUED`. A cap applied after the write is not a cap.

**A03 Software Supply Chain Failures.** Confirm no Gradle dependency was added by any task in this
feature. If one was, it is a finding.

**Virtual-thread pinning (not an OWASP row, but an availability property ADR-002 §2 requires).**
No `synchronized` block wrapping JDBC or SQS I/O, and no `ThreadLocal` held across a blocking call
in any relay class.

## Out of Scope

- **Producer-to-gateway authentication.** Still open from FEAT-005 and still recorded in
  `docs/concerns.md`. The relay adds no HTTP surface, so this feature neither worsens nor closes
  it. Do not implement it here; re-flagging it in your handoff is enough.
- **The SSRF review of outbound webhook calls (ADR-002's A01 row).** The relay makes no outbound
  HTTP call to a client URL. That review belongs to the worker's feature.
- **Redesigning anything.** If a finding requires a contract or design change, write it up and
  stop; it becomes a task, not an edit.
- Adding a `SecurityFilterChain`, a `@PreAuthorize`, or any authorization mechanism. There is no
  principal in a scheduled relay cycle and inventing one is not this task.

## Acceptance Criteria

- [ ] Each of A05, A10, A09, A01 and A03 above is checked and the finding (or its absence) is
      stated explicitly in the handoff — one line each minimum, naming the file and line checked.
- [ ] The pinning check is performed across all five files.
- [ ] Any one-line correction inside the relay's own files is applied, with a test if the
      behaviour is testable.
- [ ] Any finding requiring redesign is written up as a proposed task and **not** implemented.
- [ ] The open FEAT-005 authentication exposure is re-flagged rather than quietly dropped.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Review findings (security-engineer, 2026-09-21)

No findings requiring a fix. No code changed.

**A05 Injection** — `DeliveryPipelineJdbcRepository.claimDue` (lines 391-493). Both caller-supplied
values (`batchLimit`, `asOf`) are bound via `MapSqlParameterSource` (`:batch_limit`, `:as_of`,
lines 487-489). Every status/circuit-state literal in the `CASE` (lines 430, 440, 456, 464-467) is
a compile-time string, never concatenated from a parameter. `CLAIM_DUE_PUSH_INTERVAL` (line 43,
used at line 474) is the one compile-time constant folded into the SQL text, as the task file
permits. No finding.

**A10 Mishandling of Exceptional Conditions.**
- Claim failure is fail-closed: `RelayBatchClaimer.claimAndPromote` (lines 27-44) is
  `@Transactional` on its own bean (self-invocation note, line 15) and neither it nor
  `DispatchPendingDeliveriesUseCaseImpl.dispatch` (lines 52-78) catches around the
  `relayBatchClaimer.claimAndPromote` call (line 55) — an exception there propagates, the
  transaction rolls back, nothing is `QUEUED`, nothing is enqueued. No finding.
- Partial/failed publish performs no DB write and does not propagate:
  `DispatchPendingDeliveriesUseCaseImpl.publishBatch` (lines 81-100) wraps the entire
  `queuePort.publishBatch` call in `catch (Throwable t)` (line 95), increments
  `publishFailedCounter`, logs, and returns `0` — no repository call anywhere in this method, no
  rethrow. No finding.
- `DeliveryRelayScheduler.pollOnce` (lines 30-38) wraps the whole dispatch call in
  `catch (Throwable t)` (line 35) and only logs — the `@Scheduled` task cannot be cancelled by a
  bad cycle. No finding.

**A09 Logging & Alerting Failures.** Four counters exist —
`notification.relay.claimed`, `notification.relay.published`, `notification.relay.publish.failed`,
`notification.circuit.transition` (`DispatchPendingDeliveriesUseCaseImpl` lines 45-49) — and the
only extra tag is `direction` on the last one; no `client_id`/`subscription_id` tag on any of them.
Grepped all five reviewed files: no `content`, response body, target URL, signature header, or
secret is logged from any relay class. The two log lines that exist
(`DispatchPendingDeliveriesUseCaseImpl` lines 76, 88, 97; `DeliveryRelayScheduler` line 36) carry
only counts, `delivery_id`, and exception objects — no `subscription_id`, no payload. The
`claimed.isEmpty()` early return (line 63-65) happens before the info log at line 76, so an idle
5-second cycle logs nothing at info. No finding.

**A01 Broken Access Control.**
- No relay class injects `DeliveryQueryRepositoryPort` — `RelayBatchClaimer` injects
  `DeliveryPipelineRepositoryPort` and `SubscriptionRepositoryPort` only (lines 19-20);
  `DispatchPendingDeliveriesUseCaseImpl` injects no persistence port directly (lines 28-34). No
  finding.
- No HTTP surface: `DeliveryRelayScheduler` is a package-private `@Component` with only a
  `@Scheduled` method, no `@RestController`/`@RequestMapping`, no actuator custom endpoint. No
  finding.
- The per-subscription cap is enforced entirely inside the `claimDue` SQL (the `remaining_allowance`
  LATERAL at lines 427-451, applied as `LIMIT a.remaining_allowance` at line 461) inside the same
  `UPDATE ... RETURNING` CTE (lines 471-481) that moves rows to `QUEUED` — no Java-side cap check
  after the write. No finding.

**A03 Software Supply Chain Failures.** No new `group:artifact` was added by TASK-006-09/10/11
(`git diff 5b5ec98..HEAD -- build.gradle` shows one change: `software.amazon.awssdk:sqs:2.27.8`
moved from `testImplementation` to `implementation`, landed in commit 48a81e9, a dependency already
present and pinned to the same exact version). This is a scope promotion, not a new dependency, and
matches what `docs/concerns.md` already records as a known open follow-up ("review of the
`software.amazon.awssdk:sqs` promotion to a runtime dependency (A03)... lands with only
TASK-005-01's own acceptance criteria behind it"). Re-flagging rather than closing it: this review
did not run a live CVE scan against 2.27.8; that check is still owed and should be a task, not
assumed clean here.

**Virtual-thread pinning.** Grepped all five files for `synchronized` and `ThreadLocal`: zero
matches outside javadoc comments explicitly stating their absence
(`DeliveryPipelineJdbcRepository.java:27`, `SqsNotificationQueueAdapter.java:31,33`). No finding.

**Re-flagged, not addressed here (out of scope):** the FEAT-005 unauthenticated
`POST /internal/events` exposure (A01/A07) recorded in `docs/concerns.md` ("The ingest endpoint
ships unauthenticated") remains open. The relay adds no HTTP surface and neither worsens nor closes
it.

**Not addressed (redesign, would need a task):** none found. Every checklist item above resolved to
"no finding" against the actual code.

`./gradlew test` passes (ran locally, no failures).
