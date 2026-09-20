# Delivery worker per-message flow (implementation-facing)

Full design lives in `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` §6.2 and §4 — this note is only the implementation-relevant shape for whoever picks up the `backend-engineer` task, not a restatement of the ADR's reasoning.

## Consumer loop shape
- SQS long poll: `WaitTimeSeconds = 20`, `ReceiveMessage` batch of 10, each message processed independently on its own virtual thread.
- Scaled by KEDA on `ApproximateNumberOfMessagesVisible`, not CPU (devops task, not backend, but backend code must not assume a fixed pool size).

## Per-message steps (implement in this order — order is load-bearing, see ADR §6.2)
1. `UPDATE deliveries SET status='PROCESSING' WHERE delivery_id=? AND status='QUEUED'` — zero rows affected -> `DeleteMessage` and return, don't process further.
2. Acquire per-subscription Resilience4j bulkhead permit, 2s timeout -> on timeout, `ChangeMessageVisibility` (short delay), write nothing, return.
3. Check `subscriptions.circuit_state` -> if `OPEN`, treat like a bulkhead timeout (defensive second check; relay's due-query is the primary filter).
4. Sign (HMAC over body + timestamp, per-subscription secret) and POST with per-attempt timeouts (2s connect / 5s read).
5. Classify the response per ADR §4's table, write `delivery_attempts` row, update `deliveries.status`.
6. `DeleteMessage` — only after step 5's DB write commits, never before.

## Response classification quick reference
See ADR-001 §4 for the full table and rationale. Key implementation gotchas:
- 404 and 410 both flip `subscriptions.active = false` — not just 410.
- 429 sets `subscriptions.throttled_until`, does not touch the circuit breaker's failure count.
- Only the HTTP status code determines outcome — never inspect the response body for success/failure.
