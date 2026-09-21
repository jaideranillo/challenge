# Session: 2026-09-21 FEAT-006 delivery relay dispatch

**Date:** 2026-09-21 02:00
**Topics:** backend, database, security, errors

## Work Completed

### Files Modified/Created
- `docs/features/FEAT-006-delivery-relay-dispatch/` — 13 tasks (TASK-006-01 through 13), all `Ready for Review`. Implements ADR-002 §2.1 (relay), ADR-005 §2 (deliverability gate), ADR-006 §1.1 (SQS batch settings), ADR-006 §1.2 (circuit promotion, one-probe rule).
- `DeliveryPipelineJdbcRepository.claimDue` — rewritten as a per-subscription `LATERAL` join (driving relation `subscriptions`, `CROSS JOIN LATERAL` twice: `remaining_allowance` then the capped/locked candidate set, `FOR UPDATE OF d SKIP LOCKED` inside the inner subquery, inner `LIMIT remaining_allowance` before the outer `LIMIT :batch_limit`). Replaces an earlier `ROW_NUMBER() OVER (PARTITION BY ...)` + CTE shape that ranked candidates globally before applying the per-subscription cap.
- `NotificationQueuePort.publishBatch` now returns `PublishBatchResult(publishedCount, failedDeliveryIds)` instead of `void`; `SqsNotificationQueueAdapter` does real per-entry accounting from `SendMessageBatchResponse.failed()`.
- New: `RelayBatchClaimer` (`@Transactional`, claims + promotes `OPEN -> HALF_OPEN` per distinct subscription), `DispatchPendingDeliveriesUseCaseImpl` (commit-then-publish, no `@Transactional`), `DeliveryRelayScheduler` (`@Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")`, catches `Throwable`), `RelayProperties`/`RelaySchedulingConfig` under `adapter/in/scheduling/config`.
- `SubscriptionJdbcRepository.promoteToHalfOpen` — bug fix (TASK-006-13): removed a spurious `updated_at = :as_of` from the SET clause; the port javadoc and ADR-006 §1.2 Amendment B1 both specify `circuit_state` only.

### Problems Solved
1. **Candidate-pool starvation in the due-query.** User caught it directly (not found by an agent): the original `LIMIT`-then-cap-filter shape let one saturated subscription (e.g. 10k due rows, cap 10) fill the whole batch limit with rows the cap then rejected, starving every other subscription's due rows out of the cycle. Fixed by moving the cap inside a per-subscription `LATERAL` subquery so capping happens before the batch-wide `LIMIT`, not after. Regression test (scenario 8, `ClaimDueInFlightCapTest`): 10,000-row saturated subscription + 1-row quiet subscription, one cycle must claim the quiet row.
2. **Lost "Ready for Review" status writes, again.** Same failure mode as FEAT-005 (see `2026-09-20-concurrent-agent-stash-race.md` context / prior session note): two implementing agents (TASK-006-10, TASK-006-12) reported completion with real, verifiable work on disk (a 17KB passing test file; a fully-written findings section) but the `status:` frontmatter field itself stayed `Not Started`. Caught only by grepping every task file's status line before reporting the feature complete — not by trusting either agent's final message. Fixed by editing the two `status:` lines directly once the underlying work was confirmed present (not by re-dispatching the agents). **This is now a confirmed recurring pattern across two features — worth checking on every multi-agent feature closeout, not just when something looks off.**
3. **`grep` silently treats a task file as binary and returns nothing.** Several task `.md` files contain an em dash (non-ASCII byte), which makes BSD `grep` (macOS) classify the file as `data` and suppress all matches with no error — including a `status:` line that visibly exists when the file is read directly. `grep -a` (force text) is required for any grep over these docs. Caused a false "status field is missing" alarm before the real lost-write bug (problem 2) was confirmed by content inspection.
4. **`promoteToHalfOpen` wrote `updated_at` it wasn't supposed to.** Found by TASK-006-10's acceptance test doing a full-column diff before/after promotion — only `updated_at` changed beyond the expected `circuit_state`. The port's own javadoc and ADR-006 §1.2 Amendment B1's operation table both specify `circuit_state` only for this transition; the SQL (written earlier, under FEAT-004) didn't match its own contract. Routed through a new task (TASK-006-13, dba) rather than an ad-hoc fix, since it touched already-merged code outside the current feature's task chain.

### Technical Decisions
- Query/port contract decisions are recorded in `docs/features/FEAT-006-delivery-relay-dispatch/feature.md` and the ADRs cited above — not restated here.
- User feedback (this session): implementing agents were writing multi-paragraph ADR-citing javadoc blocks; standing instruction is now one-line comments/javadoc only, ADR rationale stays in feature/task docs. Applies repo-wide going forward.

## Status at End
- Completed: FEAT-006 all 13 tasks `Ready for Review` (status fields verified on disk, not just self-reported); `./gradlew test` green per each implementing agent's final run.
- Nothing committed to git. User reviews/commits manually.

## Notes for Next Session
- Before reporting any multi-agent feature complete, grep every task file's `status:` field with `-a` (em dashes make plain `grep` treat some of these docs as binary) and cross-check against actual file/content evidence, not the agent's own "Ready for Review" claim in its final message — two features in a row have had this exact gap.
- `docs/concerns.md` still carries: FEAT-005's unauthenticated ingest endpoint (unresolved, must close before untrusted deploy or the delivery worker), and a now-reflagged item — TASK-006-12 didn't run a live CVE scan against `software.amazon.awssdk:sqs:2.27.8` (promoted to a runtime dependency in FEAT-005), that check is still owed.
