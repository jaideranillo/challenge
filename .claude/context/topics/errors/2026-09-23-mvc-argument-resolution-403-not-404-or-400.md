---
name: mvc-argument-resolution-403-not-404-or-400
description: Spring MVC argument-binding/validation failures on this API surface as a stray 403, not the intended 400/404, because of servlet error-dispatch re-entering Spring Security's terminal denyAll chain
metadata:
  type: error
---

# Error: MVC binding/validation failures surface as 403, not 400/404

**Confirmed in three places** in `/notification_events` and `/internal/events`: a malformed `@PathVariable UUID`, an unparseable `@ModelAttribute`-bound enum query param, and a `@Valid` request-body field failure (wrong field name in a test payload).

## Mechanism

1. Spring MVC can't bind/validate the input -> throws (`MethodArgumentTypeMismatchException` or `MethodArgumentNotValidException`).
2. `DefaultHandlerExceptionResolver` resolves it via `response.sendError(400)`.
3. The servlet container internally forwards that to `/error`.
4. The `/error` forward **re-enters Spring Security's filter chain**. It matches no client-API `securityMatcher`, falls through to the terminal `anyRequest().denyAll()` chain (see `SecurityConfig`).
5. Client sees an empty-body **403**, never the 400 Spring MVC intended, and the handler method never ran.

## Fix pattern (applied to `NotificationEventController.get`/`.replay`, `ListNotificationEventsRequest.deliveryStatus`)

Never let a typed `@PathVariable`/`@ModelAttribute` bind directly to `UUID` or an internal enum. Bind as raw `String`, parse manually inside the already-authorized handler, respond the correct status from there (404 for a malformed resource id, matching ADR-007 §5.5's "foreign id == nonexistent id" rule; 400 for an invalid filter value).

## Residual risk

No structural/ArchUnit rule prevents a *new* endpoint from reintroducing this. It's currently code discipline only. Full writeup: `docs/guia-estudio-comite-arquitectura.md` §8.5.
