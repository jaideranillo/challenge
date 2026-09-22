---
id: TASK-008-06
feature: FEAT-008
title: ArchUnit rules for the tenant and framework boundary
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-01, TASK-008-04, TASK-008-05]
date: 2026-09-21
---

# TASK-008-06: ArchUnit Rules for the Tenant Boundary

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/architecture/TenantBoundaryArchTest.java` (new)
- Concern: the three structural rules of ADR-007 §5.1, expressed so they fail the build rather
  than a code review.

## The three rules, verbatim from ADR-007 §5.1

1. **No class outside `adapter/in/web/security` may construct a `TenantId`**, except test
   fixtures. Allow `src/test` sources; deny every production package other than
   `com.cobre.challenge.adapter.in.web.security`.
2. **No controller method parameter annotated `@PathVariable`, `@RequestParam` or
   `@RequestHeader` may be named or bound to `client_id`, `clientId`, `tenant` or `tenant_id`.**
   The tenant is never accepted as input. Check both the parameter name and the annotation's
   `value`/`name` attribute — a parameter named `x` bound to `@RequestParam("client_id")` is
   exactly the case this rule exists for.
3. **No class in `application/**` or `domain/**` may import `org.springframework.security.**`.**
   Security types stop at the adapter boundary.

## A fourth rule this task adds, and the rule it must NOT add

Add, because it is the compile-time half of option I-D and nothing else asserts it:

4. **Every method on the client-facing query ports takes a `TenantId` parameter.** Scope it by
   name to `DeliveryQueryRepositoryPort`, `NotificationEventQueryRepositoryPort` and
   `DeliveryAttemptQueryRepositoryPort` (TASK-008-12, TASK-008-13).

**Do not write a rule of the shape "every `port/out` method takes a `TenantId`".** ADR-007
Amendment E1 states plainly that such a rule would be wrong: `DeliveryPipelineRepositoryPort`,
`SubscriptionRepositoryPort`, `NotificationEventRepositoryPort` and
`DeliveryAttemptRepositoryPort` are cross-tenant by design and take no tenant. A rule that fails
on them would be deleted within a day, which is worse than not writing it.

## Out of Scope

- Any production code change. If a rule fails against merged code, report it in the handover; do
  not fix the production class here and do not weaken the rule to make it pass.
- Any general hexagonal layering rule beyond the three above plus rule 4 (YAGNI — this task is
  the tenant boundary, not an architecture-test suite).
- Freezing / `FreezingArchRule` violation stores.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
ArchUnit is bytecode analysis — no container, no context — so this task is fully in scope now and
nothing in it is deferred. Run this class alone (`./gradlew test --tests
"com.cobre.challenge.architecture.TenantBoundaryArchTest"`) rather than the full suite; **do not
run a bare `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] All four rules exist as separate, individually named `@ArchTest` rules, so a failure names
      which property broke.
- [ ] Rule 1 allows test fixtures and `adapter/in/web/security` and denies everything else,
      including `application/**` and `domain/**`.
- [ ] Rule 2 checks both parameter names and annotation `value`/`name` attributes, for all three
      annotations, for all four forbidden spellings.
- [ ] Rule 3 covers `application/**` and `domain/**` and the whole
      `org.springframework.security..` package tree.
- [ ] Rule 4 is scoped by name to the three client-facing query ports and does **not** apply to
      any pipeline port.
- [ ] Each rule is proven to actually bite: the handover states, per rule, how it was confirmed
      to fail (a temporary local violation, reverted before handover). A rule that passes
      vacuously because its package filter matches nothing is a defect.
- [ ] The suite runs with no Spring context and no Docker.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.

## Definition of Done

Tests written and passing locally, each rule confirmed to fail against a deliberate temporary
violation that is reverted. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Blocked (historical — resolved 2026-09-21)

Left here as history from the prior attempt. All three dependencies are now `Ready for Review`
and this task is implemented; see Handover below.

~~Not started. Dependencies unmet as of 2026-09-21:~~

~~- TASK-008-01 (devops-engineer): `build.gradle` has no ArchUnit dependency yet — the test~~
~~  framework this task needs isn't on the classpath.~~
~~- TASK-008-05 (security-engineer): `adapter/in/web/security` does not exist yet, so rule 1 (no~~
~~  class outside that package constructs `TenantId`) and rule 4's query ports (TASK-008-12/13,~~
~~  also not yet implemented) cannot be exercised or proven to actually bite per this task's own~~
~~  acceptance criteria (a rule that passes vacuously is a defect).~~

~~Per instruction, this task was left undone rather than implemented against a non-existent target.~~
~~Re-run once TASK-008-01 and TASK-008-05 are `Ready for Review` or `Done`.~~

## Handover

File added: `src/test/java/com/cobre/challenge/architecture/TenantBoundaryArchTest.java`.
`@AnalyzeClasses(packages = "com.cobre.challenge", importOptions = ImportOption.DoNotIncludeTests.class)`
so test fixtures are excluded from analysis by construction (satisfies rule 1's "allow test
fixtures" without a special-case predicate).

Four `@ArchTest` rules, each individually named:

1. `only_authenticated_tenant_resolver_constructs_tenant_id` — `noClasses().that().resideOutsideOfPackage("com.cobre.challenge.adapter.in.web.security..").should().callConstructor(TenantId.class, String.class)`.
2. `controller_parameters_never_bind_tenant_from_request` — custom `ArchCondition<JavaMethod>` that, for every method parameter annotated `@PathVariable`/`@RequestParam`/`@RequestHeader`, resolves the effective bound name (`get("value")` then `get("name")` off the real `JavaAnnotation`, falling back to the parameter's real name via `method.reflect().getParameters()[i].getName()` — confirmed `-parameters` is on for this build by inspecting the compiled `MethodParameters` attribute) and flags it if that name is `client_id`, `clientId`, `tenant` or `tenant_id`.
3. `application_and_domain_never_import_spring_security` — `noClasses().that().resideInAnyPackage("com.cobre.challenge.application..", "com.cobre.challenge.domain..").should().dependOnClassesThat().resideInAPackage("org.springframework.security..")`.
4. `client_facing_query_ports_require_tenant_id_parameter` — scoped via a `DescribedPredicate<JavaClass>` matching simple names `DeliveryQueryRepositoryPort`, `NotificationEventQueryRepositoryPort`, `DeliveryAttemptQueryRepositoryPort` only (not the pipeline ports), custom condition checks `method.getRawParameterTypes()` contains `TenantId`.

**Each rule proven to bite** (temporary local violation added, run in isolation, confirmed
`AssertionError`, then deleted — none of these survive in the working tree):

- Rule 1: temp file `domain/model/tenant/TmpRule1Violation.java` calling `new TenantId("temp")`
  from outside the security package. Failed as expected; deleted.
- Rule 2: temp file `adapter/in/web/ingest/TmpRule2ViolationController.java` with
  `tmp(@RequestParam("client_id") String x)` — the exact "param named `x`, annotation says
  `client_id`" case the task calls out. Failure message confirmed it caught the annotation
  attribute, not the parameter name: `binds forbidden tenant parameter 'client_id' via
  @RequestParam`. Deleted.
- Rule 3: temp file `application/usecase/TmpRule3Violation.java` importing
  `org.springframework.security.core.Authentication`. Failed as expected; deleted.
- Rule 4: **bites for real, against merged code, no temp fixture needed.**
  `DeliveryQueryRepositoryPort.findById(UUID, String)` and `.findPage(String, ...)` take
  `String clientId`, not `TenantId` — ADR-007 §5.2's contract table specifies `TenantId`, but the
  implemented port (TASK-008 predecessor work) uses `String`. Per this task's Out of Scope, this
  is **not** fixed here and the rule is **not** weakened to pass. Reporting it here per
  instruction: `./gradlew test --tests
  "com.cobre.challenge.architecture.TenantBoundaryArchTest"` currently ends with 3 passed / 1
  failed for exactly this reason. `NotificationEventQueryRepositoryPort` and
  `DeliveryAttemptQueryRepositoryPort` (TASK-008-12/13) don't exist yet, so the rule currently
  exercises only `DeliveryQueryRepositoryPort`; it will start covering the other two automatically
  once those ports are added under those names.

Verification commands run: `./gradlew compileJava compileTestJava` (clean) and `./gradlew test
--tests "com.cobre.challenge.architecture.TenantBoundaryArchTest"` (per-rule and full-class runs
during the bite proofs). No bare `./gradlew test` or `./gradlew build` was run.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
