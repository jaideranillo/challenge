# Session: 2026-09-20 FEAT-004 outbound persistence adapters

**Date:** 2026-09-20 23:00
**Topics:** backend, database, errors

## Work Completed

### Files Modified/Created
- `docs/features/FEAT-004-outbound-persistence-adapters/` — 20 tasks (TASK-004-01 through 20), all `Ready for Review`. Implements ADR-002 amendments C1-C2, ADR-003 A1-A4, ADR-005 D1-D3, ADR-006 B1-B3, ADR-007 E1.
- `src/main/resources/db/migration/V4__deliveries_event_created_at.sql` — additive migration (V2 not edited), adds `event_created_at`, swaps the list-endpoint index.
- `src/main/java/com/cobre/challenge/adapter/out/persistence/` — four JDBC repositories (`DeliveryPipelineJdbcRepository`, `DeliveryQueryJdbcRepository`, `DeliveryAttemptJdbcRepository`, `SubscriptionJdbcRepository`), row mappers, `DeliveryPageCursor` codec.
- `application/port/out/persistence/` — `DeliveryRepositoryPort` split into `DeliveryPipelineRepositoryPort` (cross-tenant) + `DeliveryQueryRepositoryPort` (tenant-mandatory); `SubscriptionRepositoryPort` circuit ops replaced with `tripCircuit`/`reopenCircuit`/`promoteToHalfOpen`/`closeCircuit`; `findPage`'s four `Optional` params collapsed into `DeliveryPageQuery` record (ADR-005 D3, added mid-session on user request).
- 356 tests total, Testcontainers Postgres, all green.

### Problems Solved
1. **Concurrent-agent stash race clobbered a landed fix.** Two `dba` agents (TASK-004-09, TASK-004-15) ran simultaneously; TASK-004-15's `git stash push -u` / `pop` (to isolate-compile its two files) captured and later restored the *whole* working tree, silently reverting `DeliveryAttemptJdbcRepositoryTest.java` to its pre-TASK-004-16 broken state (stale 5-arg `DeliveryAttempt` constructor) and flipping TASK-004-09's status field back to `Not Started`. Caught by re-diffing all agents' claimed changes against the actual working tree after the fact, not by any single agent noticing. Fix: never let two agents run concurrently against files in the same package/directory when either might use `git stash` for isolation — dispatch strictly sequentially whenever a file collision is even plausible, and re-verify claimed-done work against disk state before trusting an agent's own "Ready for Review" self-report.
2. **`EXPLAIN`-plan test failed because seed data was too small/uniform for the planner to prefer the index.** `SubscriptionEventTypesIndexTest` seeded 2,000 rows across 20 clients; `client_id =` alone was already selective enough (~5%) that Postgres correctly chose a seq scan over the `idx_subscriptions_event_types` GIN index — not a bug in the adapter or index. Fix: seed 100,000 rows across only 2 clients (so `client_id` is a weak ~50% predicate and `event_types @> ...` is what narrows the result), set-based `INSERT ... SELECT FROM generate_series` instead of a Java batch loop to keep it fast (~8s). General lesson: an `EXPLAIN`-based index test must make sure the *other* predicates in the query don't already dominate selectivity, or the planner will legitimately skip the index under test.
3. **Optional-as-parameter anti-pattern caught post-implementation.** `DeliveryQueryRepositoryPort.findPage` originally took four `Optional<T>` parameters (spec'd that way in FEAT-004's own Decision 1). User flagged it (Effective Java Item 55: Optional should be a return type, never a parameter type). Fixed via fast ADR-005 Amendment D3 + one task (TASK-004-20): collapsed into a single `DeliveryPageQuery` record parameter (whose *fields* still use `Optional`, which is fine — the anti-pattern is specifically about method parameters).

### Technical Decisions
- **`DeliveryPageQuery` record** (`application.port.out.persistence.dto`) replaces four `Optional` params on `findPage`. See ADR-005 Amendment D3.
- Rest of the port/contract decisions are recorded in `docs/features/FEAT-004-outbound-persistence-adapters/feature.md` — not restated here.

## Status at End
- Completed: FEAT-004 all 20 tasks `Ready for Review`; `./gradlew test` green, 356 tests, no regressions.
- Nothing committed to git. User reviews/commits manually.

## Notes for Next Session
- User is time-constrained, 4 more features queued after this one — expect fast-turnaround ADR-amendment-style fixes rather than full new-ADR ceremony when small contract issues surface mid-implementation.
- Concurrency lesson from this session (problem 1 above) should inform how dispatch waves are sequenced for the next 4 features: don't parallelize `dba`/`backend-engineer` agents that might touch files in the same directory, even if the task dependency graph technically allows it.
