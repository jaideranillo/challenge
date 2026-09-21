---
id: ADR-001
title: Transactional Outbox Architecture and Queue Transport
status: Accepted
date: 2026-09-20
authors: software-architect (Atlas)
supersedes:
superseded_by:
---

# ADR-001: Transactional Outbox Architecture and Queue Transport

## Status

Accepted <!-- change only by the user: Proposed | Accepted | Rejected | Superseded by ADR-NNN -->

## Context

See `docs/architecture-overview.md` for full context; this ADR covers the foundational choice of where delivery state lives and what the queue is for.

## Options Considered

### Option A: Direct synchronous delivery from the event consumer (no outbox)

The service consumes a platform event, looks up the subscription, and performs the HTTPS POST inline, retrying in-process.

- Pros:
  - Least moving parts; fastest to implement.
  - Lowest latency in the happy path.
- Cons:
  - No durable record of intent before the attempt: a crash mid-attempt loses the notification entirely.
  - Retry state lives in memory, so retries do not survive a restart or a deploy.
  - A slow or hanging client endpoint directly consumes the event-consumption capacity and back-pressures unrelated clients (noisy-neighbor).
  - Cannot satisfy "store final delivery information" as a source of truth, nor `POST /replay`, without bolting a table on anyway.
  - Fails the resiliency non-functional outright.

### Option B: Persist to the queue first, treat the queue as the source of truth

The gateway writes the delivery intent straight to SQS; workers consume, deliver, and only then write an audit row to Postgres.

- Pros:
  - Simple write path; queue provides fan-out and visibility timeouts for free.
  - Native dead-letter queue support handles definitive failure.
- Cons:
  - Dual-write problem with no transaction: the platform event can be accepted and the enqueue can fail (or vice versa), silently losing notifications.
  - The API endpoints (`GET` list/detail, `replay`) need a queryable, filterable store; a queue is not queryable, so a table is required regardless.
  - Message retention caps (SQS: 14 days maximum) make the queue unusable as a system of record for delivery history.
  - Replay of a definitively failed delivery becomes a DLQ-redrive operation rather than an API call against a row the client can see.

### Option C: Transactional outbox in Postgres, queue as transport only (whiteboard design)

The gateway validates the subscription and writes a `deliveries` row in the same transaction as accepting the event. A relay publishes a lightweight pointer message to SQS. Consumers claim rows with `SELECT ... FOR UPDATE SKIP LOCKED`, perform the HTTPS attempt on a virtual thread, and write the outcome back to the `deliveries` table. The queue only wakes consumers; losing a message loses nothing, because a sweeper re-publishes any row left in a non-terminal state past its `next_attempt_at`.

- Pros:
  - Single source of truth in Postgres: durable, queryable, filterable, directly backing all three API endpoints.
  - No dual-write hazard: the delivery intent is committed with the event in one transaction.
  - `SKIP LOCKED` gives safe horizontal scaling of consumers with no double-claim and no distributed lock service.
  - At-least-once delivery semantics with an explicit, inspectable retry schedule that survives restarts and deploys.
  - Replay is a state transition on an existing row, not an infrastructure operation.
  - Queue outage degrades latency, not correctness: the sweeper keeps draining the outbox.
- Cons:
  - More components than Option A: gateway, relay, sweeper, consumer.
  - The outbox table is a write-hot table needing index and retention discipline (partitioning, archival).
  - Requires the DB to absorb the claim traffic; polling cadence needs tuning.
  - At-least-once means clients must tolerate duplicates; this obligation must be documented and supported with a delivery id header.

## Decision

**Adopt Option C: a transactional outbox in PostgreSQL (`deliveries` table) as the single source of truth, with the queue as pure transport, `SELECT ... FOR UPDATE SKIP LOCKED` claiming, virtual-thread-based delivery workers, and a self-service REST API reading from the same table.**

### 1. Core decision: the database is the source of truth, the queue is transport

**Decision.** All delivery state lives in PostgreSQL. SQS carries a single delivery attempt pointer and nothing more. A message never represents the lifecycle of a delivery — the row does.

**Why.** A queue cannot be queried, cannot be filtered by status, and deletes its own history (SQS retention caps at 14 days). The self-service API needs exactly those three things: query, filter, history. Making the queue authoritative would mean maintaining delivery state in two places that cannot be kept transactionally consistent — the dual-write hazard named in Option B's cons.

**Consequences that follow from this and are not separately negotiable:**

- **No separate outbox table.** `deliveries` is not a business table plus a parallel outbox table kept in sync — it *is* the outbox. There is exactly one write path: the gateway inserts the `deliveries` row (status `PENDING`) in the same DB transaction that accepts the event. There is no dual write between "record the delivery" and "enqueue the delivery" — only one of those is a write at all.
- **Nothing is ever enqueued before it is committed.** The relay only ever reads rows that already exist and are already committed; it cannot publish a pointer message for a delivery that isn't durably recorded, because the row is the precondition for the publish, not a side effect of it.
- **A message lost in SQS is recoverable; a row lost in PostgreSQL is not.** The design is built around that asymmetry, not around making the queue reliable. This is why the relay/sweeper's `SKIP LOCKED` claim over `deliveries` — not SQS redelivery — is the guaranteed-delivery path (ADR-002 §2): losing a message only costs latency, because the row it would have pointed to is still there and still due. There is no equivalent recovery path for a row that was never committed, which is exactly why the write-then-publish ordering above is non-negotiable.
- **Duplicate enqueues are harmless and therefore not defended against with coordination.** Two pointer messages for the same `deliveries` row (relay double-publish, SQS at-least-once redelivery, the relay's own due-query racing a normal publish, ADR-002 §2.1) are safe by construction: the consumer's claim is `UPDATE ... WHERE status = 'QUEUED'` (the state-guard from ADR-003 §1.1/§2), so only the first claim succeeds — every subsequent claim attempt on an already-`PROCESSING` or already-terminal row affects zero rows and the consumer discards the message. No deduplication table, no message-id tracking, no exactly-once queue configuration is needed; the row's `status` column *is* the deduplication mechanism.

### 1.1 Queue technology: SQS, not Kafka

**Decision.** Amazon SQS Standard for the delivery queue, with a redrive policy to a DLQ.

**Why not Kafka.** Kafka is a partitioned, ordered log. Webhook delivery is the opposite shape: independent per-message work, with retry horizons spanning seconds to hours, requiring isolation per destination.

- **No native delay.** Retrying in 15 minutes means either sleeping the consumer (which blocks the partition) or building tiered retry topics by hand.
- **Head-of-line blocking.** Partitioning by `client_id` means one client with a dead endpoint freezes every client sharing that partition.

SQS Standard has no partitions, so a message reattempts on its own schedule and a failing destination never affects another. `DelaySeconds` covers short backoff natively; longer horizons are covered by the relay (ADR-002 §2), which the design needs regardless of queue choice.

**Ordering is deliberately not offered.** FIFO would reintroduce head-of-line blocking within a message group and cap throughput, and staggered retries break ordering anyway — a retried event arrives after events that came later (ADR-004 §1, "Ordering"). Instead, the outbound webhook body carries `created_at` (the platform event's creation timestamp, `notification_events.created_at`, ADR-003 §3) with `event_id` as a deterministic tiebreak, so a client that cares can order or discard stale notifications on its own side (envelope shape in ADR-004 §1.1). This does not change Q6: the platform still does not guarantee delivery order, it only gives the client the information needed to reconstruct it if they want to.

**Why a timestamp and not a per-client sequence counter.** An earlier draft of this ADR proposed a `deliveries.sequence_number`, monotonic per `client_id` and assigned at ingest. That is the correct *idea* — hand the client a total order it can sort on — implemented the wrong way: a monotonic-per-client counter needs a per-client row that is read-modify-written on every single ingest, which serializes all inserts for that client behind one row. That is exactly the hot-row pattern ADR-006 §1.2 deliberately refuses for the circuit breaker's failure count, and it would be worse here, because the circuit breaker only writes on a state *transition* while an ingest counter writes on every event. Two ways out exist: a **global** Postgres `SEQUENCE` (monotonic and increasing, gapped per client — fine, because only relative order matters, not density), or **no new column at all**, using `created_at` plus `event_id`. This ADR takes the second. A global sequence would still be a new column, a new database object, and a second ordering key to explain to clients, and it would buy nothing `created_at` does not already provide: `created_at` is already stored (ADR-003 §3), already indexed for the list endpoint's date-range filter (ADR-005 §1, ADR-003 §3), and — per ADR-004 §1.1 — already inside the signed webhook body, so the client can trust it. `event_id` breaks ties deterministically for two events sharing a timestamp. Adding a column whose only job is to re-encode an ordering two existing fields already express is the YAGNI violation, so `sequence_number` is dropped rather than reimplemented.

**Not chosen: a managed webhook-delivery service** (e.g. a hosted outbound-webhook product). Correct answer for a real product on total cost of ownership; excluded here because building this mechanism is the exercise.

## Consequences

**Becomes easier**
- The queue is swappable (SQS -> Kafka -> Postgres `LISTEN/NOTIFY` -> plain polling) by replacing one `port/out` adapter; the design degrades to pure DB polling if the queue is removed entirely.

**Becomes harder / debt created**
- No per-client ordering guarantee; if a client later needs it, that is a new ADR.

**Blocks / unblocks**
- Unblocks: the schema + migration ADR/task (DBA), the Spring Security and SSRF-defense design (security-engineer), and the outbound webhook signing scheme.
- Blocks nothing already accepted; this is ADR-001.
- Follow-up ADRs likely needed for: webhook payload signing (HMAC vs. mTLS), subscription management API (out of scope here), and outbox retention/partitioning if volume warrants it.

## OWASP / Security Impact

| OWASP Top 10:2025 | Exposure in this design | Mitigation direction (one line) |
| --- | --- | --- |
| **A03 Software Supply Chain Failures** | This design introduces at least one new dependency (an AWS SQS SDK or equivalent queue client). | Pin versions, run dependency scanning in the build; flagged for the devops-engineer/security-engineer tasks. |

## Assumptions

Carried from the master Q-list in `docs/architecture-overview.md`; these two are owned by this ADR.

- **Q1 — Queue technology (resolved: Amazon SQS).** Confirmed by the user: the delivery queue is AWS SQS Standard with long polling and a redrive policy to a DLQ (§1.1, ADR-006 §1.1), and the two settings in ADR-006 §1.1 (`VisibilityTimeout`, `maxReceiveCount`) are real SQS settings, not placeholders — `maxReceiveCount = 3` as the whiteboard has it, `VisibilityTimeout` raised to 30s there. The rest of the design stays queue-agnostic anyway: SQS is load-bearing in exactly one place, the `NotificationQueuePort` implementation, and the design degrades to pure relay polling with no correctness change if the queue is removed entirely (§1, ADR-002 §2.1). Nothing downstream of this entry is blocked on it.
- **Q6 — Per-client ordering (resolved: not required).** The case does not ask for ordered delivery, and this design deliberately does not provide it — concurrent workers plus independent retry schedules make a retried event land after a later one (ADR-004 §1, "Ordering"), and FIFO would reintroduce head-of-line blocking (§1.1). The resolution is unchanged in substance and only changes in mechanism: give the client what it needs to order events itself rather than serialize the pipeline. That material is `created_at` (the platform event's creation timestamp) with `event_id` as a deterministic tiebreak, both carried inside the **signed** webhook body (§1.1, ADR-004 §1.1, ADR-003 §3) — so a client that cares can order or discard stale notifications on its own side, on data an intermediary cannot alter. This replaces the earlier `deliveries.sequence_number` (monotonic per `client_id`, assigned at ingest), which is dropped as a column: a per-client monotonic counter needs a per-client row written on every ingest, serializing that client's inserts behind one hot row for an ordering two already-stored fields express (full reasoning in §1.1 and ADR-003 §3). If a client ever genuinely needs the *platform* to guarantee order, that is per-client serialization and a new ADR, not a revision of this one.

## Downstream

All seven ADRs (ADR-001 through ADR-007) are now `Accepted`. Feature/task breakdown lives at `docs/features/FEAT-002-webhook-notification-delivery/`.
