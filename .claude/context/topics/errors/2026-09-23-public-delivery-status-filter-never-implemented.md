---
name: public-delivery-status-filter-never-implemented
description: GET /notification_events?delivery_status=... never worked for any public vocabulary value (pending/completed/failed) - bound directly to the internal 7-state enum instead
metadata:
  type: error
---

# Error: `delivery_status` filter never worked for the public vocabulary

ADR-003 §1 defines the public/internal mapping (`pending` -> 4 states, `completed` -> `DELIVERED`, `failed` -> `DEAD`+`FAILED`) but it was never actually implemented against the query path. `ListNotificationEventsRequest.deliveryStatus` bound directly to the internal `DeliveryStatus` enum, and `DeliveryQueryJdbcRepository`'s SQL used `status = :status` (single value) - could never express `pending` (4 states) or `failed` (2 states) even if the binding worked.

## Fix

New `PublicDeliveryStatus` enum (`domain/model/delivery/enums`) with `fromWire(String)` (never throws), `internalStates()` (returns a `Set<DeliveryStatus>`), and `of(DeliveryStatus)` for the reverse direction. Chain: request binds raw `String` (see the companion 403-vs-400 error note) -> controller parses -> `QueryNotificationEventsCommand.status: Optional<PublicDeliveryStatus>` -> use case expands via `internalStates()` -> `DeliveryPageQuery.statuses: Set<DeliveryStatus>` -> SQL `status IN (...)`, one named param per state.

Also found and fixed: `NotificationEventListItemResponse.status` and `ReplayAcceptedResponse.status` were leaking the internal enum to the wire (violates ADR-003 §1.1) - both now serialize `PublicDeliveryStatus` via `@JsonValue`.

No new ADR - this is ADR-003 §1's existing decision, previously unimplemented. Full detail: `docs/guia-estudio-comite-arquitectura.md` §2.2.
