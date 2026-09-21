---
id: TASK-004-18
feature: FEAT-004
title: "SubscriptionJdbcRepository: deactivate and setThrottledUntil, the response-classification side effects, with tests"
status: Ready for Review
agent: dba
depends_on: [TASK-004-17]
date: 2026-09-20
---

# TASK-004-18: `deactivate` and `setThrottledUntil`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The two subscription-level side effects of ADR-004 §1's response classification. Separate from the circuit operations (TASK-004-19) because they answer a different question: these are the client telling us the endpoint is wrong or busy, not the platform concluding it is down.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepository.java` (add two methods)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepositoryTest.java` (extend)
- Concern: the classification-driven subscription writes.

### `deactivate(UUID subscriptionId)`

ADR-004 §1: a 404 or 410 at the registered URL means the endpoint no longer exists there, so the subscription stops being deliverable.

```sql
UPDATE subscriptions SET active = false, updated_at = now()
 WHERE subscription_id = :subscription_id AND active
```

- Guard on `active` so a repeat is a no-op returning `false` rather than a redundant write to a row several workers may touch at once.
- `updated_at` from the database's `now()`: this method's signature has no `Instant` and is **not** to be changed here (it is pre-existing, and the clock-source inconsistency is logged in `docs/concerns.md`). Assert `updated_at` as a bound, not an exact value.
- **`verification_state` is not touched.** ADR-005 §3 is explicit that the two mean different things and are set by different parties; deactivating must not erase the platform's verification proof, or a reactivation would silently require a new handshake.
- **No reactivation method.** ADR-004's Q12 ships no reactivation path in v1, deliberately. Do not add one "for symmetry".

### `setThrottledUntil(UUID subscriptionId, Instant until)`

ADR-004 §1: a 429 is escalated to the subscription so every other in-flight delivery for that client backs off too, instead of each one rediscovering the same 429.

```sql
UPDATE subscriptions SET throttled_until = :until, updated_at = :until_or_now
 WHERE subscription_id = :subscription_id
```

- **Unconditional on the current value**, and this is the one write in the feature that is deliberately last-writer-wins: two workers both seeing a 429 should land the later `Retry-After`, not have the second silently lose. Comment it, because it looks like a missing guard next to every other conditional write in this class.
- Consider `GREATEST(throttled_until, :until)` to make it monotonic. **Decide and state the choice in a comment**; either is defensible, but a reviewer must see that it was a decision. Monotonic is the safer reading, since it cannot shorten a throttle window another worker just extended.
- **`throttled_until` is not a circuit state.** ADR-004 §1: a 429 does not count toward the breaker. This method must not touch `circuit_state`, `circuit_opened_at`, `circuit_backoff` or `consecutive_opens`.
- Return `boolean` from the affected row count, `false` for an unknown id, never throwing.

### Required tests

1. `deactivate` on an active subscription returns `true`, sets `active = false`, advances `updated_at`.
2. `deactivate` on an already-inactive subscription returns `false` and changes nothing.
3. `deactivate` leaves `verification_state`, `verified_at` and every circuit column untouched.
4. `deactivate` on an unknown id returns `false` without throwing.
5. A deactivated subscription is **no longer returned by `findActiveForEvent`**. The behavior ADR-004 §1 actually wants; a passing unit write with a still-matching read would be a false green.
6. `setThrottledUntil` sets the value and returns `true`.
7. Throttling does **not** change `circuit_state` or any other circuit column.
8. A throttled subscription **is still returned by `findActiveForEvent`** (TASK-004-17 requirement 4: the gate is the relay's, not ingest's).
9. Whichever monotonicity choice was made is asserted: if `GREATEST`, an earlier `until` does not shorten an existing window; if last-writer-wins, it does. The test must match the comment.
10. `setThrottledUntil` on an unknown id returns `false` without throwing.

Real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC.

## Out of Scope

- The four circuit operations (TASK-004-19).
- The reads (TASK-004-17), except tests 5 and 8 which assert the interaction.
- No reactivation method, no `verification_state` write, no verification handshake.
- No response classification. The adapter is told what happened; ADR-004 §1's table is the domain's.
- No `Retry-After` parsing. `until` arrives computed.
- No signature change to `deactivate`.

## Acceptance Criteria

- [ ] Two methods, one `UPDATE` each, both returning `boolean` from the affected row count, neither throwing on zero rows.
- [ ] `deactivate` is guarded on `active` and touches only `active` and `updated_at`.
- [ ] `setThrottledUntil` is documented as deliberately unguarded, with the monotonicity choice stated in a comment.
- [ ] Neither method touches any circuit column.
- [ ] No reactivation method was added.
- [ ] `deactivate`'s signature is unchanged from the committed port.
- [ ] All values bound as named parameters.
- [ ] Tests written and passing: all ten above against real Postgres via `TestcontainersConfiguration`. Tests 5 and 8 exercise `findActiveForEvent` after the write. No H2, no mocked JDBC. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.SubscriptionJdbcRepositoryTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. YAGNI: no reactivation path.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** both return `false` on a lost race rather than throwing inside the worker's outcome path. **A06:** test 3 is the control that deactivation cannot erase the verification proof, which would otherwise let a later reactivation deliver to an unverified endpoint. **A09:** neither method logs `target_url` or `secret_ref`.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
