---
id: TASK-006-03
feature: FEAT-006
title: NotificationQueuePort.publishBatch returns PublishBatchResult
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-006-03: `NotificationQueuePort.publishBatch` returns `PublishBatchResult`

## Feature

FEAT-006

## Assigned Agent

`backend-engineer` — contract only. The adapter that satisfies it is TASK-006-04.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/queue/NotificationQueuePort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/queue/dto/PublishBatchResult.java` (new)
  - `src/test/java/com/cobre/challenge/application/port/out/queue/NotificationQueuePortTest.java`
- Concern: a partially failed batch publish is expressible.

### The change

```java
void publish(DeliveryPointer pointer);                            // unchanged
PublishBatchResult publishBatch(List<DeliveryPointer> pointers);  // was: void
```

```java
package com.cobre.challenge.application.port.out.queue.dto;

public record PublishBatchResult(int publishedCount, List<UUID> failedDeliveryIds) { }
```

Compact constructor: reject a negative `publishedCount`, reject a null `failedDeliveryIds`, and
defensively copy it with `List.copyOf` (Effective Java Items 17 and 50). An empty batch is a
legal input and yields `new PublishBatchResult(0, List.of())` — never `null`, never an exception
(Item 54).

### Why this is a contract addition and not a design change

ADR-002 §2.1: "`SendMessageBatch` is partially fallible. Failed entries stay `QUEUED` and are
recovered by the already-pushed clock — no special-case error handling needed for a partial batch
failure." The *handling* is nothing, and stays nothing. The *reporting* is not nothing: the relay
use case must populate `DispatchPendingDeliveriesResult.publishedCount` and must count failed
entries so a degraded publish path is visible to operations (ADR-002 §3, OWASP A09). A `void`
method cannot express either.

**Nothing about the recovery mechanism changes.** No caller of this port compensates, rolls back,
re-publishes in-cycle, or mutates a `deliveries` row in response to `failedDeliveryIds`. The
pushed clock is the only recovery path. State that in the javadoc, in those terms, so the next
implementer does not "helpfully" add a retry.

The place `publishBatch` is called from on a failure path is also worth naming in the javadoc:
the failed ids are for **observability**, and a caller that uses them for anything else is
contradicting ADR-002 §2.1.

The `NotificationQueuePort` javadoc's existing statement — that both the gateway's single
`SendMessage` and the relay's `SendMessageBatch` write the same envelope through one port
(ADR-004 SS1) — stays true and must be preserved.

## Out of Scope

- `publish(DeliveryPointer)`. Ingest's path is untouched; do not give it a return value "for
  symmetry".
- `SqsNotificationQueueAdapter` beyond whatever minimal edit the compiler forces. Its real
  implementation is TASK-006-04; if you must touch it to keep the build green, return
  `new PublishBatchResult(pointers.size(), List.of())` and leave a `// TASK-006-04` marker.
- `DeliveryPointer`. It stays exactly four flat fields (ADR-004 SS1); do not add a fifth.
- `IngestPublishDispatcher` and every ingest test.

## Acceptance Criteria

- [ ] `publishBatch` returns `PublishBatchResult`; `publish` is unchanged.
- [ ] `PublishBatchResult` is an immutable record with a validating compact constructor and a
      defensive copy of the list.
- [ ] `failedDeliveryIds` is documented and implemented as empty-never-null.
- [ ] Javadoc states that failed ids are observability-only and that the pushed `next_attempt_at`
      is the sole recovery mechanism (ADR-002 §2.1).
- [ ] `NotificationQueuePortTest` covers the record's validation and the empty-batch case.
- [ ] `./gradlew build` compiles and `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
