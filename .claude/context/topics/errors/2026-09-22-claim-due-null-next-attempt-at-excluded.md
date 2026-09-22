# Error: claimDue silently excluded every freshly-ingested delivery

**Date:** 2026-09-22

## Symptom

Full delivery pipeline stall: events ingested fine (rows land in `deliveries`, status `PENDING`),
but the relay never claims/dispatches any of them. FEAT-009's `notification.delivery.attempt.latency`
dashboard panels showed no data no matter how long the stack ran.

## Root cause

`DeliveryPipelineJdbcRepository.claimDue`'s candidate WHERE clause filtered
`d.next_attempt_at <= :as_of`. Per `V2__deliveries.sql`'s column comment, a freshly-ingested
delivery has `next_attempt_at = NULL` by design (NULL means "due now"). In Postgres,
`NULL <= anything` evaluates to NULL (never true), so every such row was excluded from every
claim cycle, forever. The method's *other* subquery (in-flight cap, a few lines above) already had
the correct `IS NULL OR ...` pattern — only the main candidate predicate was missing it.

## Fix

```sql
-- before
AND d.next_attempt_at <= :as_of
-- after
AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= :as_of)
```

`src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java`,
`claimDue` method. Regression test added:
`ClaimDuePredicateTest.nextAttemptAt_null_isDueNow_claimed`.

## Detection

Found while verifying TASK-009-07 (Grafana dashboard) live against real traffic — panels 1/2 had
no data to show despite the pipeline "looking" healthy in isolation. Confirmed via direct Postgres
query before/after (0/41 matched vs 41/41 matched with the NULL branch).
