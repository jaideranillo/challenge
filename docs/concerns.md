# Concerns

Deviations from ADR text, recorded per CLAUDE.md's "Design authority" rule
(implement as specified, log disagreement here rather than reopening the ADR).

## ADR-005 §1 lists `RetryPolicy` under `domain/model`; implementation places it under `domain.policy`

`RetryPolicy`, `ResponseClassifier`, `AttemptOutcome` and `TransportFailure` are
stateless decision rules over the domain aggregates, not aggregate state
themselves, so FEAT-003 groups them in `domain.policy` rather than
`domain.model` (software-architect review, 2026-09-20). No behavior differs
from ADR-005 §1's intent — this is a package-naming refinement only.

## Domain package layout further split by aggregate scope, and by kind within each aggregate

Post-implementation, at the user's explicit request, `domain.model` and
`application.port.*` were reorganized twice:

1. Package-by-feature: `domain.model.delivery` / `.event` / `.subscription`,
   `application.port.in.pipeline` / `.selfservice`, `application.port.out.persistence`
   / `.queue` / `.webhook` (software-architect taxonomy, 2026-09-20).
2. Within each aggregate, enums and the aggregate's own exception were further
   split into `<aggregate>.enums` and `<aggregate>.exception` subpackages
   (e.g. `domain.model.delivery.enums.DeliveryStatus`,
   `domain.model.delivery.exception.IllegalDeliveryTransitionException`) — a
   user decision made after being shown the package-by-kind tradeoff (fragments
   a single concept across packages, no cohesion gain). Recorded here as a
   deviation from typical hexagonal package-by-feature guidance, not reopened
   further since it is a working-tree organizational choice, not an ADR
   decision.

## Port command/result records extracted from nested to `dto` subpackages

At the user's explicit request, every use-case/port interface's nested
`record`/`enum` (Command, Result, DTO types previously declared inside the
interface file, e.g. `RegisterNotificationEventUseCase.RegisterNotificationEventCommand`)
was extracted to its own top-level file under a `dto` subpackage of that
port's package (`application.port.in.pipeline.dto`,
`application.port.in.selfservice.dto`, `application.port.out.persistence.dto`,
`application.port.out.queue.dto`, `application.port.out.webhook.dto`),
following the same package-by-kind convention already applied to
`domain.model`. `ReplayDeliveryResult` and its `Accepted`/`Rejected`
implementations were kept as their own top-level files directly in
`application.port.in.selfservice` rather than under `dto`, since they are a
behavioral result hierarchy, not plain data-transfer records. TASK-003-10/11/12/13
updated to reflect the current file layout (2026-09-20).
