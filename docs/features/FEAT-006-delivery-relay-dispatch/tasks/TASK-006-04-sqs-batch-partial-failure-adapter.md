---
id: TASK-006-04
feature: FEAT-006
title: SqsNotificationQueueAdapter — partial-failure-aware SendMessageBatch
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-03]
date: 2026-09-21
---

# TASK-006-04: `SqsNotificationQueueAdapter` — partial-failure-aware `SendMessageBatch`

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapter.java`
- Concern: chunk, send, and report which entries SQS refused.

### The change

The merged `publishBatch` already chunks at 10 (`BATCH_CHUNK_SIZE`, ADR-006 §1.1's batch size)
and already ignores the response. Keep the chunking exactly as it is. Add:

1. **Read `SendMessageBatchResponse.failed()`** on every chunk.
2. **Map each failed entry's `Id` back to a `deliveryId`.** The entry `Id` is already the
   chunk-relative index as a string, so the mapping is total: parse it and index the chunk. Do not
   change the `Id` scheme to a `deliveryId` — SQS batch entry ids have a restricted character set
   and a bounded length, and an index is what the existing code and tests already assume.
3. **Accumulate across chunks** and return
   `new PublishBatchResult(pointers.size() - failed.size(), failedIds)`.

### Failure modes, precisely

- **A per-entry failure is a normal outcome, not an exception.** It is reported in the result and
  nothing else happens — no retry, no `ChangeMessageVisibility` (removed from the design entirely,
  ADR-006 §1.1 point 1), no write to `deliveries`. The already-pushed `next_attempt_at` is the
  recovery mechanism (ADR-002 §2.1).
- **A whole-request failure still throws.** If the SDK throws on a chunk — credentials, network,
  queue gone — let it propagate, exactly as the class's existing javadoc says it does. The caller
  decides; TASK-006-07 specifies that decision. Do **not** add a `try/catch` here that converts a
  thrown chunk into "all entries failed": that would make an outage indistinguishable from SQS
  refusing a malformed body, and the two want different alerts.
  - The one consequence worth a comment: chunks are sent sequentially, so a throw on chunk 3
    leaves chunks 1-2 published and 4+ unsent. That is safe — the unsent rows are already `QUEUED`
    with a pushed clock and come back on a later cycle — but it means the thrown case yields no
    `PublishBatchResult` at all, which is why the caller counts a thrown batch separately.

### Constraints that already hold and must keep holding

- **No `synchronized` anywhere on this path.** The class javadoc explains why: `SqsClient`'s
  blocking I/O unmounts a virtual thread correctly, and a `synchronized` guard would pin the
  carrier for a full network round trip (ADR-002 §2).
- The `traceparent` message attribute stays on every entry (ADR-002 §3.1).
- The queue URL stays resolved once at construction.
- No `content`, no payload, no URL is ever logged from this class (ADR-002 §3.1's PII rule).

## Out of Scope

- `publish(DeliveryPointer)`, the envelope, `NotificationEnvelope`, `SqsClientConfig`,
  `SqsProperties`, and the queue's own settings (`VisibilityTimeout`, `maxReceiveCount` — already
  configured, ADR-006 §1.1).
- Tests. TASK-006-05 owns them.
- Any retry, backoff, or DLQ logic in this adapter.

## Acceptance Criteria

- [ ] `publishBatch` returns an accurate `PublishBatchResult` across multiple chunks.
- [ ] Chunk size stays 10.
- [ ] Failed entry `Id`s are mapped back to `deliveryId`s by chunk index; the entry `Id` scheme is
      unchanged.
- [ ] A thrown SDK exception still propagates; no catch-all converts it into a result.
- [ ] The sequential-chunk consequence is stated in a comment.
- [ ] No `synchronized`, no `ThreadLocal`, no new executor in this class.
- [ ] Nothing is logged that carries payload, target URL, or secret material.
- [ ] `./gradlew build` compiles.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
