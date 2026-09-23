---
name: circuit-breaker-half-open-transitions-uninstrumented
description: Only 2 of 4 circuit breaker state transitions (CLOSED_TO_OPEN, OPEN_TO_HALF_OPEN) had a notification.circuit.transition metric - the probe outcome (HALF_OPEN_TO_CLOSED/HALF_OPEN_TO_OPEN) was invisible in Grafana
metadata:
  type: error
---

# Error: half-open probe outcome never counted in `notification.circuit.transition`

ADR-002 §3 requires all four circuit-state transitions instrumented by direction. `CLOSED_TO_OPEN` (`AttemptDeliveryUseCaseImpl`, worker trip) and `OPEN_TO_HALF_OPEN` (`DispatchPendingDeliveriesUseCaseImpl`, relay promotion) had counters; `DeliveryOutcomeWriter.applyProbeCircuitTransition` called `subscriptionPort.closeCircuit`/`.reopenCircuit` but never incremented the metric - so a Grafana viewer could see a circuit trip but never confirm it recovered.

## Fix

Added `MeterRegistry` to `DeliveryOutcomeWriter`; increments `HALF_OPEN_TO_CLOSED`/`HALF_OPEN_TO_OPEN` only when the conditional write actually succeeded (`tolerate()` now returns the boolean instead of just logging), never on a lost first-writer-wins race - matches the existing convention on `OPEN_TO_HALF_OPEN` (`circuitPromotedCounter.increment(claim.promotedCount())`, a confirmed-write count, not an attempted one).

Verified live end-to-end this session: forced 4x 500 against one subscription -> `CLOSED_TO_OPEN`; waited the 60s local-profile cooldown cap -> `OPEN_TO_HALF_OPEN`; reset the webhook stub to 200 -> circuit closed in Postgres and `HALF_OPEN_TO_CLOSED` appeared in Prometheus.

New Grafana panels added to `docker/grafana/dashboards/notification-delivery.json`: "Circuit breaker transitions by direction" and "Webhook stub — requests received" (the latter filters `http_server_requests_milliseconds_count{uri="/local/webhook-stub/receive"}`, confirms the client is actually being called vs. the worker merely claiming to). Full detail: `docs/guia-estudio-comite-arquitectura.md` §6.1, §9.6.
