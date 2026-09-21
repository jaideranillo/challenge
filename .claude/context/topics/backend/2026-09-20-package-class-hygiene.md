---
name: backend-package-class-hygiene
description: Mandatory package/class structure rules added to the backend-engineer agent — one type per file, thin controllers, sub-divided adapter packages, model vs dto placement
metadata:
  type: backend
---

# Package & Class Hygiene (backend-engineer, mandatory)

Added to `.claude/agents/backend-engineer.md` after review of TASK-001-03's first pass:

- **One type per file.** A nested `interface`/`sealed interface`/enum representing a distinct concept never lives inside an unrelated host class (a recorder, a controller, a service) — it gets its own top-level file.
- **Controllers are thin.** No private helper/utility methods in a controller class. Each handler validates/maps input, delegates to a collaborator, maps the result. Helpers move onto the collaborator they operate on (e.g. `RecordedRequest.from(HttpServletRequest)` static factory instead of a controller-private `headersOf`/`bodyOf`).
- **Adapter packages are sub-divided by concern**, never a flat bucket — `adapter/in/web` is not itself a leaf package.
- **Records/DTOs never live nested inside a service/recorder/controller.** Placement by role: `model/` for internal/domain-facing types, `dto/` for wire-serialized payloads. Caveat: don't reuse `model` as a subpackage name for adapter-internal state — `domain/model` is reserved vocabulary in this codebase's hexagonal layout (CLAUDE.md); adapter-only state that plays a "model" role but isn't a domain concept should get a different name (e.g. `behavior/`) to avoid the collision.
- **Static factory methods (`from`/`of`/`to`, Effective Java Item 1) over public constructors** for conversions between types.

Concrete precedent: `com.cobre.challenge.adapter.in.web.local.webhookstub` — `LocalWebhookStubController` (thin), `LocalWebhookStubRecorder` (store only), `dto/RecordedRequest` (wire payload, `from(HttpServletRequest)` factory), `behavior/ForcedBehavior` (sealed interface, not `model/` — see the naming-collision note above).
