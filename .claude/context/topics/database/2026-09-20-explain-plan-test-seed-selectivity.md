# EXPLAIN-plan index tests: seed data must make the target predicate the selective one

**Date:** 2026-09-20
**Topics:** database, testing

## Symptom
`SubscriptionEventTypesIndexTest` (proving `event_types @> ARRAY[...]` uses the GIN index `idx_subscriptions_event_types`, not a seq scan) failed at 2,000 seeded rows across 20 clients. The query also filters `client_id = 'client-idx-0'`; at that seed shape `client_id` alone narrowed to ~5% of the table, cheap enough for Postgres to correctly pick a seq scan and filter in memory rather than also touch the GIN index. Not a bug in the adapter, the query, or the index — correct planner behavior for the data shape given.

## Root Cause
An `EXPLAIN`-based "does the planner use my index" test is only meaningful if the predicate under test is the one actually driving selectivity. If another predicate in the same `WHERE` clause is already selective enough on its own, the planner has no reason to combine it with a second index scan, and the test's premise ("enough seeded rows that a seq scan isn't cheapest") silently fails to hold even at large row counts, if those rows are skewed in a way that keeps the *other* predicate cheap.

## Fix
Seed 100,000 rows across only 2 clients (not 20) — `client_id` becomes a weak ~50% predicate, and `event_types @> ...` becomes the predicate that actually narrows the result, forcing the planner to combine `idx_subscriptions_client_active` and `idx_subscriptions_event_types` via `BitmapAnd`. Seed set-based (`INSERT ... SELECT FROM generate_series` with a CTE) rather than a Java batch loop — a 100k-row client-side batch is slow to build/send, the set-based version runs entirely in Postgres (~8s test runtime). Always run `ANALYZE` before `EXPLAIN`; never toggle `enable_seqscan` — the question is whether the planner *chooses* the index given real stats, not whether it's capable of using it.

General rule for any future `EXPLAIN`-plan test in this repo: check every predicate in the query, not just the one under test, for how selective it is against the seed shape — the least selective predicate the planner sees is the one worth stress-testing on.
