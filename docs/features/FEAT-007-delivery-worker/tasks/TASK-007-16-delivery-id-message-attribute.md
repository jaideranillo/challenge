---
id: TASK-007-16
feature: FEAT-007
title: delivery_id as an SQS message attribute on the publisher
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-16: `delivery_id` message attribute

## Priority — take this first

Raised by the Tech Lead on 2026-09-21. This task has no dependencies and **must land before any
consumer task and before any consumer test**, in particular TASK-007-18. The DLQ consumer exists
to mark a row `FAILED` when the body cannot be deserialized, and the `delivery_id` **message
attribute** is the only thing that makes that possible. Writing those tests first would mean
writing them against a transport that cannot satisfy them. Schedule it immediately after the
configuration tasks, or in parallel with them.

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapter.java` (modified)
  - `src/test/java/com/cobre/challenge/adapter/out/messaging/SqsNotificationQueueAdapterTest.java` (modified)
- Concern: close a gap between merged code and ADR-004 §1. Nothing else in the adapter changes.

### The gap

ADR-004 §1, closing the "`delivery_id` extraction cannot fail" argument: "**`delivery_id` is
additionally set as an SQS message attribute**, transported and length-validated by SQS separately
from the body. The DLQ consumer reads the attribute first and only falls back to parsing the body.
Correlation therefore survives a body that fails JSON parsing outright."

The merged adapter sets only the `traceparent` attribute. Without the `delivery_id` attribute, the
DLQ consumer (TASK-007-18) has nothing but the body to correlate from, which is precisely the
failure mode that paragraph exists to prevent.

### The change

Extend the existing private attribute builder so every published message — **both** `publish` and
every entry of `publishBatch`, since ADR-004 §1 insists both publishers go through one envelope
construction site — carries:

| Attribute | Type | Value |
|---|---|---|
| `delivery_id` | `String` | `pointer.deliveryId().toString()` |
| `traceparent` | `String` | unchanged, still conditional on presence |

`delivery_id` is unconditional: a pointer always has one. Keep the attribute name as a constant
next to the existing `TRACEPARENT_ATTRIBUTE`.

Nothing else moves: the body stays exactly the four-field `NotificationEnvelope`, the queue URL
resolution stays at construction, the chunking stays at 10, and no exception handling is added.

### `traceparent` was checked for the same defect and does not have it

The Tech Lead asked whether `trace_context` shares this root cause. It does not: the merged
adapter already publishes `traceparent` as a message attribute (conditionally, when the pointer
carries one) in addition to the body, which is exactly what ADR-002 §3.1 requires, and the
consumer additionally has the persisted `deliveries.trace_context` as a second fallback. **Do not
change `traceparent` behavior** — including not making it unconditional, since an absent
traceparent is a legitimate state (`Optional.empty()`, ADR-002 Amendment C3). Confirm this in your
handover so the check is recorded rather than repeated.

## Out of Scope

- The DLQ consumer (TASK-007-18) and the main listener (TASK-007-17).
- The message body shape, `NotificationEnvelope`, and `DeliveryPointer`.
- The relay, its use case and its scheduler.
- Any change to `publishBatch`'s partial-failure handling or its return contract.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. The merged `SqsNotificationQueueAdapterTest` already mocks `SqsClient`; extend it in the
same style.

Required unit scenarios:
- `publish` sends a request whose message attributes contain `delivery_id` equal to the pointer's
  id as a string
- every entry of a `publishBatch` chunk carries the same attribute, including a pointer with no
  traceparent
- the `traceparent` attribute behavior is unchanged (present when the pointer has one, absent
  otherwise)
- the message body is byte-identical to what the adapter produced before this change

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] Every published message, single or batched, carries a `delivery_id` String attribute.
- [ ] The attribute name is a constant, spelled `delivery_id`.
- [ ] `traceparent` behavior and the message body are unchanged, and the handover records that
      `traceparent` was checked for the same defect and does not have it.
- [ ] This task is completed **before** TASK-007-17 and TASK-007-18 are started.
- [ ] No other adapter behavior changes: no new exception handling, no queue-URL re-resolution,
      no chunk-size change.
- [ ] The existing tests still express their original intent; only additions were made.
- [ ] Every unit scenario listed above is covered.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- Added `DELIVERY_ID_ATTRIBUTE = "delivery_id"` constant next to `TRACEPARENT_ATTRIBUTE` in
  `SqsNotificationQueueAdapter`. Renamed the private `traceparentAttribute` builder to
  `messageAttributes`; it now unconditionally sets `delivery_id` (`pointer.deliveryId().toString()`)
  and, unchanged, sets `traceparent` only when `pointer.traceparent()` is present. Both `publish`
  and `toBatchEntries` (used by `publishBatch`) call this single builder, satisfying ADR-004 SS1's
  "one envelope construction site" requirement.
- No other adapter behavior changed: body is still the four-field `NotificationEnvelope`, queue URL
  resolution stays at construction, chunk size stays 10, no new exception handling.
- `traceparent` defect check confirmed: the merged adapter already published `traceparent` as a
  conditional message attribute in addition to the body (ADR-002 SS3.1), and the DLQ/consumer side
  additionally has `deliveries.trace_context` as a persisted fallback. This was NOT changed, and was
  NOT made unconditional, per the task's explicit instruction (`Optional.empty()` remains a
  legitimate state, ADR-002 Amendment C3).
- Test note: the merged `SqsNotificationQueueAdapterTest` is NOT a mocked-`SqsClient` plain-JUnit
  test as the task's Testing section assumed — it's a `@SpringBootTest` integration test against
  real LocalStack SQS via Testcontainers. Rewriting it to a mock-based style would be a test-infra
  change beyond this task's declared scope (modify the existing file, nothing else), so the required
  scenarios were added in the file's existing style instead:
  - `publishPutsAReceivableMessageWithTheFourFields` now also asserts the `delivery_id` attribute
    equals the pointer's id.
  - `traceparentAbsentYieldsNoMessageAttribute` now also asserts `delivery_id` is present even
    when `traceparent` is absent.
  - New `publishBatchCarriesDeliveryIdAttributeOnEveryEntryAcrossChunks` (13 pointers, all with
    `Optional.empty()` traceparent, spanning two `sendMessageBatch` chunks) asserts every received
    entry's `delivery_id` attribute matches its body's `deliveryId`.
  - `bodyHasNoFifthField` (pre-existing, untouched) continues to prove the body is unchanged.
  These tests require Testcontainers/LocalStack and were not run, per instruction.
- Verification: `./gradlew compileJava compileTestJava` currently fails, but on a pre-existing,
  unrelated error in an untracked file (`WebhookEnvelope.java`, from other in-progress work not
  part of this task) missing a `tools.jackson.annotation` dependency. Confirmed unrelated by
  stashing this task's changes and reproducing the identical failure. The two files in this task's
  scope have no compile errors on manual review.
