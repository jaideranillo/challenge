---
id: TASK-004-20
feature: FEAT-004
title: Collapse findPage's four Optional parameters into a DeliveryPageQuery record
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-004-14, TASK-004-15]
date: 2026-09-20
---

# TASK-004-20: Collapse `findPage`'s four `Optional` parameters into a `DeliveryPageQuery` record

## Feature

FEAT-004

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Signature change only, no behavior change. `DeliveryQueryRepositoryPort.findPage` currently takes four `Optional` parameters; `Optional` is a return type, never a parameter type (Effective Java Item 55), and four same-shaped positional arguments are confusable at the call site. Per **ADR-005 Amendment D3**, the filters collapse into one immutable record.

- File(s):
  1. **New:** `src/main/java/com/cobre/challenge/application/port/out/persistence/dto/DeliveryPageQuery.java` — record with exactly these components, in this order:
     `Optional<Instant> eventCreatedFrom`, `Optional<Instant> eventCreatedTo`, `Optional<DeliveryStatus> status`, `Optional<String> cursor`.
     Compact canonical constructor rejects `null` for any component (`Objects.requireNonNull`) — absence is `Optional.empty()`, never `null`. Add one static factory `DeliveryPageQuery.unfiltered()` returning all-empty, since most call sites and tests want it; no other factories, no builder (YAGNI).
  2. **Modified:** `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryQueryRepositoryPort.java` — `findPage` becomes:
     ```
     DeliveryPage findPage(String clientId, DeliveryPageQuery query, int limit)
     ```
     `clientId` stays a separate, mandatory, first-position parameter (the tenant rule of ADR-005 Amendment D1 must stay visible in the signature). `limit` stays a plain `int`. Update the Javadoc `@param` block to match; keep every existing semantic statement (`event_created_at` bounds, keyset tuple, tenant-as-predicate) verbatim.
  3. **Modified:** `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepository.java` — adapt the method to the new parameter list by destructuring `query` at the top of the method. The SQL, the predicate assembly, the binding, the cursor decode/encode and the `DeliveryPage` construction are all unchanged.
  4. **Modified:** `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryFindPageTest.java` — update every `repo.findPage(...)` call site to the new signature. Same assertions, same cases, same coverage; use `DeliveryPageQuery.unfiltered()` where all four filters were `Optional.empty()`.

- Concern: one port signature, its single adapter, and its tests.

## Out of Scope

- `DeliveryPage` — unchanged, do not touch.
- `findById` — unchanged, do not touch.
- Any other port, adapter, or test file (`DeliveryQueryJdbcRepositoryTest`, the pipeline/attempt/subscription ports and adapters, the cursor codec, the row mappers).
- The SQL text, the keyset semantics, the clamping/default-window rules, the cursor format.
- No new validation rules on the filter values (no "from must precede to" check) — that gate lives in the web adapter/use case, not here.
- No `port/in` changes; no use case consumes this port yet.

## Acceptance Criteria

- [ ] `DeliveryQueryRepositoryPort.findPage` declares no `Optional` parameter; the only `Optional` in the file is `findById`'s return type.
- [ ] `DeliveryPageQuery` is a record in `application.port.out.persistence.dto`, all four components non-null-checked, with `unfiltered()`.
- [ ] `DeliveryQueryJdbcRepository` compiles against the new signature with identical SQL and identical binding.
- [ ] Every existing test in `DeliveryFindPageTest` still passes unchanged in intent (Testcontainers, real Postgres — no H2), including the tenant-isolation and cursor-replay cases.
- [ ] `./gradlew build` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced — A01 (tenant still a bound predicate, `clientId` still a separate mandatory parameter) and A05 (cursor still decoded to typed values before binding, malformed cursor still rejected) are unaffected by this change.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
