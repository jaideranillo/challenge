---
id: ADR-007
title: Self-service API security - JWT resource server, structurally enforced tenant isolation, scope model and rate limiting
status: Accepted
date: 2026-09-20
authors: software-architect (Atlas)
supersedes:
superseded_by:
---

# ADR-007: Self-Service API Security - JWT Resource Server, Structurally Enforced Tenant Isolation, Scope Model and Rate Limiting

## Status

Accepted <!-- change only by the user: Proposed | Accepted | Rejected | Superseded by ADR-NNN -->

## Context

ADR-001 through ADR-006 (the delivery-pipeline design, all status `Accepted`) name the security exposure of the public self-service API but explicitly defer the design: their OWASP rows say "Named here, not designed here" for A01 (IDOR and SSRF), A05, A07 and A09, and ADR-001's Consequences section lists "the Spring Security and SSRF-defense design (security-engineer)" as unblocked work. This ADR designs the authentication, authorization and tenant-isolation half of that. It does **not** design the SSRF/egress controls (outbound direction), the webhook signing scheme, or producer authentication for the ingest endpoint - those are separate follow-ups and are only referenced here where a boundary has to be drawn.

**This ADR depends on ADR-001 through ADR-006, all `Accepted`.** If any of them is later superseded or materially changed, the endpoint surface and the `client_id`-keyed data model this ADR is written against change with it.

### The surface being secured

From ADR-005 section 1, three client-facing endpoints, all reading the `deliveries` table joined to `notification_events`:

| Endpoint | Nature |
| --- | --- |
| `GET /notification_events` | Read, list, filtered and keyset-paginated |
| `GET /notification_events/{notification_event_id}` | Read, single resource, includes full attempt history |
| `POST /notification_events/{notification_event_id}/replay` | Write, inserts a new `deliveries` row, re-enters the delivery pipeline |

Plus surfaces that are **not** part of this chain and must not accidentally inherit its rules:

- The producer ingest endpoint (ADR-002 section 1.1, Q10): platform-internal callers authenticating with AWS IAM SigV4, a different caller and a different identity system. No shared credential with the client API.
- Actuator (ADR-002 section 3.1): liveness and readiness probes are called by an orchestrator with no token; metrics and every other actuator endpoint are operator surface.
- The relay, worker and DLQ consumer (ADR-002 sections 2.1, 2.2): background beans with no HTTP request and therefore no authenticated principal at all. They legitimately operate across every tenant.

### Constraints fixed before this decision

- Java 21, Spring Boot 4.1.1, Spring Security, Spring MVC on virtual threads (`spring.threads.virtual.enabled=true`). No WebFlux, no reactive types (CLAUDE.md).
- Spring Data JDBC / `NamedParameterJdbcTemplate`, PostgreSQL only.
- Hexagonal: `domain/model` is framework-free. Security is an `adapter/in/web` plus cross-cutting configuration concern and must not leak Spring Security types into the domain or into use cases.
- This service **never issues tokens**. Production tokens come from a corporate IdP (Keycloak or Cognito); that IdP's configuration is out of scope. Local development and the demo validate against a local RSA public key, with test tokens produced by a helper script committed to the repository. No IdP container in `compose.yaml`, no user table, no credential storage of any kind in this service.
- The tenant is the `client_id` claim. It must reach the repository layer as a query predicate and must be resolvable **only** from the authenticated principal.
- One human developer. Whatever is chosen has to be implementable and testable by one person.

### The problem that actually needs solving

Authentication is the easy half: `oauth2ResourceServer().jwt()` plus a public key is a handful of configuration lines. The hard half is the one the delivery design's A01 (IDOR) exposure names, and it is a design problem rather than a configuration problem:

> a missing per-resource tenant check leaks or replays another client's notification

ADR-005 states the rule correctly ("every query filters by the authenticated `client_id` ... never from a request parameter") but states it as a rule. A rule is enforced by whoever remembers it. The service will grow endpoints, and the failure mode is silent: an unfiltered query returns rows and passes every test written against a single-tenant fixture. The requirement from the user is explicit on this point - the tenant filter must be structurally enforced, such that the unfiltered path is uncompilable or unreachable, not merely discouraged.

## Options Considered

Two independent decisions are recorded here: how tokens are validated (T), and how the tenant predicate is enforced (I). The rate-limiting placement and key-per-environment strategy follow from those and are recorded in the Decision section rather than as competing options.

### T-A: Opaque tokens with introspection against the IdP

- Pros:
  - Immediate revocation; the IdP is consulted on every request.
  - No key material of any kind in this service.
- Cons:
  - A network call to the IdP on every single API request, on the critical path, with its own timeout and failure mode. A10: if introspection is unreachable the endpoint either fails open (unacceptable) or fails closed (the whole API is down whenever the IdP blips).
  - Creates a hard runtime dependency on the IdP for local development and for tests, which directly contradicts the "no IdP container, tests run on Testcontainers" constraint.
  - Adds a second deployment topology concern for a single developer.

### T-B: Self-contained JWT, signature verified locally (chosen)

- Pros:
  - Zero network calls on the request path; validation is a signature check plus claim assertions, microseconds, no external failure mode.
  - Local development and tests need only a public key and a signing script, no IdP, no container, no network. This is exactly the constraint the user set.
  - Spring Security has first-class support (`spring-boot-starter-oauth2-resource-server`), so the decision costs configuration rather than code.
- Cons:
  - Revocation is not immediate; a stolen token is valid until it expires. Mitigated by short token lifetimes (an IdP concern) and by the fact that this service holds no money-moving operation, only read access and a replay of a notification the client already owns.
  - Key rotation has to be handled (JWKS in production solves it; the local static key does not rotate).

### T-C: mTLS, client certificate as the identity

- Pros:
  - Very strong authentication, no bearer token to steal.
  - Already the shape used for machine-to-machine traffic in many payment platforms.
- Cons:
  - The corporate IdP is a given in this environment and already issues JWTs; mTLS would mean a parallel identity system with its own issuance, distribution and revocation story, for the same set of clients.
  - Terminating mTLS is typically a load balancer concern, so the application would still be reading a header it has to trust, which reintroduces the same trust-boundary question with less tooling around it.
  - Rejected as duplicative, not as unsound. Same reasoning ADR-002's Q10 used to pick IAM over a bespoke internal credential: ride the identity system that already exists.

### I-A: Convention plus code review ("always add `WHERE client_id = ?`")

- Pros:
  - Zero mechanism, zero cost.
- Cons:
  - This is the status quo the user explicitly rejected. It fails silently and it fails at exactly the moment a new endpoint is added under time pressure.
  - Unit tests written against a single-tenant fixture pass whether or not the predicate is present.

### I-B: Compile-time enforcement - the tenant is a mandatory parameter of every tenant-scoped port method

Every `port/out` method that reads client-owned data takes a `TenantId` as its first parameter, and no overload exists without one. A use case cannot call the repository without producing a `TenantId`, and a `TenantId` is produced in exactly one place.

- Pros:
  - The unfiltered call does not compile, because there is no method to call. This is the "uncompilable" bar the user asked for.
  - It is a plain type in the domain, framework-free, and it makes the tenant visible in every signature, so a reviewer reading a port interface sees the constraint rather than having to remember it.
  - Zero runtime cost, zero new infrastructure.
- Cons:
  - It guarantees the parameter is *passed*, not that the adapter's SQL actually *uses* it. An adapter author can accept a `TenantId` and forget to bind it into the `WHERE` clause. The compiler cannot see inside a SQL string.
  - It does not protect an ad hoc `NamedParameterJdbcTemplate` query written outside the port structure.

### I-C: Runtime enforcement at the database - PostgreSQL Row Level Security

Enable RLS on the tenant-owned tables with a policy of the shape `client_id = current_setting('app.client_id')`, and set that session variable from the authenticated principal at the start of every API transaction.

- Pros:
  - Closes exactly the gap I-B leaves open: a query that forgets the predicate returns zero rows instead of another tenant's rows. The unfiltered path is *unreachable* even when it compiles, including for raw `JdbcTemplate` SQL.
  - Fails closed by construction: if the session variable was never set, the policy matches nothing.
  - The database, which already is the single source of truth (ADR-001 section 1), becomes the single point of enforcement as well.
- Cons:
  - Requires role discipline: RLS is bypassed by the table owner and by a `BYPASSRLS` or superuser role, so the application must not connect as either.
  - The internal pipeline (ingest, relay, worker, DLQ consumer) is legitimately cross-tenant and must not be subject to the policy, which means two database roles and therefore two connection pools, or role switching per transaction.
  - Adds a policy object per table that the DBA must maintain alongside the schema, and a new class of "why does this query return nothing" debugging.

### I-D: I-B and I-C together (chosen)

Compile-time structure so the unfiltered call cannot be written, plus a database-level policy so it cannot succeed if it somehow is.

- Pros:
  - The two mechanisms fail independently. Forgetting the parameter is caught by the compiler; forgetting to bind it in SQL is caught by the policy; writing a query outside the ports entirely is caught by the policy.
  - Both are cheap. The compile-time half is a parameter and an ArchUnit test; the runtime half is a policy per table and a `SET LOCAL` per transaction.
- Cons:
  - Two mechanisms to explain and to keep consistent, and the two-database-role requirement lands on the DBA task.
  - Slightly more setup in tests: a Testcontainers fixture has to create both roles for the RLS behavior to be exercised at all (a test running as the table owner would silently bypass every policy and prove nothing).

## Decision

**Adopt T-B and I-D: a stateless JWT resource server with deny-by-default filter chains, and tenant isolation enforced both at compile time (a mandatory `TenantId` port parameter, constructible only from the authenticated principal) and at runtime (PostgreSQL Row Level Security under a non-bypassing application role).**

### 1. Security design overview

```mermaid
flowchart TB
  subgraph Edge["Edge (out of scope, but where rate limiting starts)"]
    GWY[API gateway / WAF<br/>volumetric + IP rate limit, TLS termination]
  end

  subgraph AdapterIn["Adapter:In - adapter/in/web"]
    FC[SecurityFilterChain - API<br/>stateless, CSRF off, deny by default]
    JWTF[BearerTokenAuthenticationFilter<br/>+ JwtDecoder: RS256, iss/aud/exp/client_id]
    RL[Per-client rate limit filter<br/>read budget vs replay budget]
    ARG[TenantId argument resolver<br/>principal -> TenantId]
    CTRL[NotificationEventController<br/>@PreAuthorize scope check]
  end

  subgraph App["Application"]
    UC[QueryNotificationEventsUseCase<br/>GetNotificationEventUseCase<br/>ReplayDeliveryUseCase<br/>every method takes TenantId]
    PORT[port/out: DeliveryRepositoryPort<br/>findByTenant TenantId, ...]
  end

  subgraph Domain["Domain"]
    TID[TenantId - plain record, no Spring]
  end

  subgraph AdapterOut["Adapter:Out - adapter/out/persistence"]
    TXN[Tenant session binder<br/>SET LOCAL app.client_id at tx start]
    REPO[JDBC adapter<br/>binds :client_id in every WHERE]
  end

  PG[(PostgreSQL<br/>RLS policies on tenant tables<br/>role: challenge_api, no BYPASSRLS)]

  GWY --> FC --> JWTF --> RL --> ARG --> CTRL --> UC --> PORT --> REPO --> PG
  TXN --> PG
  UC -.uses.-> TID
  REPO -.reads.-> TXN
```

Four properties hold by construction rather than by discipline:

1. No endpoint is reachable without a valid token, because the chain's terminal rule is `anyRequest().denyAll()` and each permitted path is listed explicitly.
2. No use case can query client-owned data without a `TenantId`, because no such method signature exists.
3. No `TenantId` can be manufactured from request input, because the only production factory reads the `Authentication`.
4. No SQL can return another tenant's rows under the API's database role, because the RLS policy filters them out before the query sees them.

### 2. Filter chain shape

**Three `SecurityFilterChain` beans, ordered, each matching a disjoint path set, plus a terminal deny chain.** Separate chains rather than one chain with many `requestMatchers`, because the three surfaces authenticate differently: a JWT, an IAM SigV4 signature, and nothing at all. Merging them would mean one chain whose rules have to encode "authenticated, but by which of three mechanisms", which is where permit-all mistakes are made.

| Order | Chain | Matches | Rules |
| --- | --- | --- | --- |
| 1 | Actuator | `/actuator/**` | `/actuator/health/liveness`, `/actuator/health/readiness` and `/actuator/health` permitted with no authentication (orchestrator probes carry no token, ADR-002 section 3.1). **Every other actuator endpoint**, `/actuator/metrics`, `/actuator/prometheus`, `/actuator/env`, `/actuator/loggers` and the rest, requires authentication and the `ops` authority, and is additionally expected to be unreachable from the public ingress (a deployment concern, stated here so the devops task owns it). Health details (`management.endpoint.health.show-details`) set to `never` for unauthenticated callers: a probe needs the status code, not the component breakdown, and the breakdown names internal dependencies (A02). |
| 2 | Ingest | `/internal/**` (ADR-002 section 1.1 producer endpoint) | AWS IAM SigV4 verification, per ADR-002's Q10. **Explicitly not the JWT chain.** This ADR does not design that verification; it reserves the path prefix and states that this chain is `authenticated()` with a distinct authentication mechanism and no client JWT accepted. Flagged for the security-engineer follow-up. |
| 3 | Client API | `/notification_events/**` | The chain designed below. |
| 4 | Terminal | `/**` | `denyAll()`. Anything not matched above is refused, so a new controller mapped to a new path is dead on arrival until someone deliberately adds it to a chain. This is the concrete meaning of deny by default: the default for an unlisted path is 403, not "whatever the last chain happened to say". |

**Chain 3, the client API, in order:**

1. `securityMatcher("/notification_events/**")`.
2. `csrf(csrf -> csrf.disable())`. Stateless bearer-token API, no cookie is ever issued or read, so there is no ambient credential for a cross-site request to ride. CSRF protection defends cookie-based sessions; with none, the token would be protecting nothing while breaking every non-browser client.
3. `sessionManagement(SessionCreationPolicy.STATELESS)`. No `JSESSIONID`, no `HttpSession`, no `SecurityContextRepository` persistence. Each request is authenticated from its own `Authorization` header. This also matters for the concurrency model: a session store would be shared mutable state on the request path for a workload whose whole scaling story is stateless instances.
4. `cors`: disabled by default. These are server-to-server APIs and no browser origin is known to need them. If a client dashboard is later built, that is an explicit allow-list, never `*`, and never `allowCredentials(true)` with a reflected origin.
5. Security response headers: HSTS, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` on every response (delivery history is tenant data and must not be cached by an intermediary), `Content-Security-Policy: default-src 'none'` since the API returns only JSON.
6. `oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` with the decoder and validators of section 3 and the authority mapper of section 4. This is where `BearerTokenAuthenticationFilter` sits.
7. **Per-client rate limit filter**, placed after authentication (section 6 explains why it cannot be earlier).
8. Authorization rules:
   - `GET /notification_events` and `GET /notification_events/{id}` require the `notifications:read` authority.
   - `POST /notification_events/{id}/replay` requires the `notifications:replay` authority.
   - `anyRequest().denyAll()` inside this chain too, so a newly added `/notification_events/something-else` mapping is refused until a rule is written for it.
9. Exception handling: a custom `AuthenticationEntryPoint` and `AccessDeniedHandler` emitting RFC 9457 problem-detail JSON with no stack trace, no exception class name, and no indication of which validation failed (an expired token, a wrong audience and a bad signature all produce the same 401 body; telling an attacker which check failed is free reconnaissance). 401 for absent or invalid authentication, 403 for a valid token lacking the authority, 404 for a resource belonging to another tenant, per section 5.

**Method-level authorization is used in addition, not instead.** `@PreAuthorize("hasAuthority('notifications:replay')")` on the controller method duplicates rule 8 deliberately: the URL-level rule protects the path, the annotation protects the handler if the path mapping is ever changed or a second mapping is added to the same method. Two cheap checks that fail independently.

**Virtual thread note.** `SecurityContextHolder` uses a `ThreadLocal`, which is correct and safe for one virtual thread per request. Two constraints follow and belong in the backend task: the strategy must stay `MODE_THREADLOCAL` (never `MODE_INHERITABLETHREADLOCAL`, which would copy context into spawned threads and, in a virtual-thread world, spread a principal in ways nobody reasoned about), and no security context is propagated into the relay or worker beans. Those run with no principal on purpose (section 5.4). Nothing in this chain holds a lock across I/O, so no pinning risk is introduced here.

### 3. Token contract

This service validates; it never issues. The contract below is what a token must satisfy to be accepted, and it is the specification the corporate IdP must be configured against and the local helper script must produce.

**Signature**

| Property | Rule |
| --- | --- |
| Algorithm | `RS256` only. The decoder is configured with an explicit single-algorithm allow-list, so `alg: none` and any HMAC algorithm are rejected before signature verification. This is not theoretical: algorithm confusion (a token signed with HS256 using the RSA public key as the HMAC secret) is the classic JWT break, and an allow-list is the mitigation. |
| Key resolution, production | JWKS from the IdP's `issuer-uri` discovery document, with `kid` matching, cached with the JWKS default refresh. Rotation is the IdP's job and costs this service nothing. |
| Key resolution, local and test | A single static RSA public key, no `kid` matching. See section 7. |
| Key size | Minimum 2048-bit RSA. A key smaller than that is a startup failure, not a warning (section 7). |

**Claims**

| Claim | Required | Validation |
| --- | --- | --- |
| `iss` | yes | Must equal the configured issuer exactly. Per-environment configuration; a mismatch is a hard 401. |
| `aud` | yes | Must contain this service's audience identifier. Without an audience check, a token minted for any other service in the same realm is accepted here, which is a lateral-movement path, not a formality. |
| `exp` | yes | Standard expiry with a bounded clock skew of 30 seconds (not the 60-second Spring default; 30 is sufficient for NTP-synced hosts and halves the replay window of an expired token). |
| `iat` | yes | Present and not in the future beyond the skew. Also used for the maximum-lifetime rule below. |
| `nbf` | optional | Honored if present. |
| `sub` | yes | The subject, logged for audit. Not used for authorization. |
| `client_id` | **yes** | **The tenant.** Non-blank, and conforming to a strict format (see below). A token without it is rejected with 401 at the decoder, before any handler runs. This is a custom `OAuth2TokenValidator`, not a controller-side check, so no endpoint can ever see a principal without a tenant. |
| `scope` or `scp` | yes | Space-delimited string or array of strings. Mapped to authorities per section 4. A token with none of this service's scopes authenticates successfully and is then denied 403 by the authorization rules. |

**Additional validation rules**

- **Maximum token lifetime.** `exp - iat` must not exceed a configured ceiling (proposed: 1 hour; a proposal, not a derived number, in the same class as ADR-004's Q5 and ADR-006's Q7 tuning values). A correctly configured IdP will not issue longer, but this service should not be the component that accepts a 10-year token because an IdP client was misconfigured. Enforced as a validator, so the check exists here regardless of what the IdP does.
- **`client_id` format.** Validated against a strict pattern (proposed: 1 to 64 characters, `[A-Za-z0-9_-]`, matching the sample data's `CLIENT001` shape from `docs/challenge/notification_events.json`). Two reasons. First, the value becomes a bound SQL parameter and a PostgreSQL session variable; binding is the injection defense (A05) and the format check is the second layer, particularly for the `SET LOCAL` path of section 5.3 where the value is a string literal in a session-configuration statement. Second, a malformed tenant should fail at the boundary with a clear 401 rather than several layers deeper as an empty result set.
- **No authorization decision reads any other claim.** Roles, group memberships and organizational claims that the corporate IdP may include are ignored. Only `scope` drives authority, only `client_id` drives tenancy. Anything else in the token is, at most, logged.

**What deliberately is not done.** No token introspection, no revocation list, no per-request IdP call, no nonce or jti replay store. Revocation is bounded by the token lifetime, which is the accepted trade of T-B, and this API's worst case on a stolen token is read access plus replay of the victim's own already-failed notifications. If that trade ever stops being acceptable, the answer is a shorter lifetime at the IdP, not new state in this service.

### 4. Authority model: read versus replay

**Two distinct scopes, least privilege by construction.**

| Scope | Grants | Rationale |
| --- | --- | --- |
| `notifications:read` | `GET /notification_events`, `GET /notification_events/{id}` | The ordinary client integration: poll delivery status, investigate a complaint. This is the credential a client embeds in a dashboard or a monitoring job. |
| `notifications:replay` | `POST /notification_events/{id}/replay` | Re-enters the delivery pipeline and causes real outbound traffic to the client's own endpoint. Strictly more dangerous and strictly more expensive than a read. |
| `ops` | Non-health actuator endpoints | Operator surface, not a client scope. Never granted to a client token. |

**Why replay is a separate scope rather than a role or a flag.** Replay is not a read with a side effect; it is a write that creates work in the pipeline (ADR-005 section 1: a new `PENDING` row that the relay will pick up and a worker will act on). A client that only needs to observe delivery status has no reason to hold the ability to generate outbound traffic, and the blast radius of a leaked read-only token should not include "can cause the platform to hammer my endpoint". Splitting the scope means the client chooses which credential goes where: the read scope in the dashboard, the replay scope in the narrower operational tool that actually needs it.

**Authority mapping.** A `JwtAuthenticationConverter` with a `JwtGrantedAuthoritiesConverter` configured with an **empty authority prefix** and the `scope`/`scp` claim as source, so the scope string `notifications:replay` becomes the authority `notifications:replay` verbatim. No `SCOPE_` prefix, so what is written in the security configuration is exactly what is written in the token and there is no prefix mismatch class of bug. Unknown scopes present in the token are mapped to authorities and simply never referenced by any rule.

**`notifications:replay` does not imply `notifications:read`.** No hierarchy, no `RoleHierarchy` bean. A token holding only the replay scope can replay and cannot list. This is slightly inconvenient and deliberately so: implication is where least privilege quietly erodes, and a client that needs both asks for both. The IdP is free to issue both in one token.

### 5. Tenant-scoping mechanism

This is the core of the ADR. Four layers, each of which fails independently. Layers 1 and 2 make the unfiltered path uncompilable; layers 3 and 4 make it unreachable.

#### 5.1 Layer 1 - `TenantId` is a domain type with one production factory

`TenantId` is a plain Java record in `domain/model`, framework-free, validating its own format on construction (section 3's pattern). It is not a `String`, so it cannot be confused with an event id, a subscription id or any other identifier at a call site.

Its **only** production construction site is `AuthenticatedTenantResolver` in `adapter/in/web/security`, which reads the `client_id` claim from the `Jwt` on the current `Authentication` and returns a `TenantId`. That class is the single point where a tenant enters the application, and it is the single class a security review has to read to verify where tenancy comes from.

Controllers never construct one and never see the claim. A Spring MVC `HandlerMethodArgumentResolver` binds `TenantId` as a controller method parameter, so a handler signature reads:

> `list(TenantId tenant, @Valid ListQuery query)`

and the resolver, not the handler, is what consults the security context. A developer writing a new endpoint gets the tenant by declaring the parameter, which is easier than any wrong alternative. That matters more than any prohibition: the enforced path has to be the path of least resistance or it will be routed around.

**The prohibition is still stated and still enforced**, by three ArchUnit rules in the test suite (which is where a structural rule that the compiler cannot express belongs, so that it fails the build rather than a review):

1. No class outside `adapter/in/web/security` may construct a `TenantId`, except test fixtures.
2. No controller method parameter annotated `@PathVariable`, `@RequestParam` or `@RequestHeader` may be named or bound to `client_id`, `clientId`, `tenant` or `tenant_id`. The tenant is never accepted as input, and this rule is what makes that mechanically true instead of aspirationally true.
3. No class in `application/**` or `domain/**` may import `org.springframework.security.**`. Security types stop at the adapter boundary, which is the hexagonal rule this ADR must not break while solving a security problem.

#### 5.2 Layer 2 - every tenant-scoped port method takes a `TenantId`, and no overload omits it

`port/out` interfaces that read or write client-owned data expose **no** method without a `TenantId` parameter. Contract-level shapes, to be finalized in the feature breakdown:

| Port | Method shape |
| --- | --- |
| `DeliveryRepositoryPort` | `findByTenant(TenantId, DeliveryQuery)` returning a page; `findByIdForTenant(TenantId, DeliveryId)` returning `Optional<Delivery>`; `insertReplay(TenantId, ReplayCommand)` |
| `DeliveryAttemptRepositoryPort` | `findByDeliveryForTenant(TenantId, DeliveryId)` returning a list, empty when absent |

`Optional` and empty collections, never `null`, consistent with ADR-005's port conventions.

**There is no `findById(DeliveryId)`.** Not deprecated, not discouraged: absent. A developer who wants to load a delivery without a tenant has nothing to call, and adding such a method is a visible, reviewable change to a port interface rather than an invisible omission inside a SQL string. That is the "uncompilable" property, and it is the reason the parameter is on the port rather than being read from a thread-local inside the adapter: a thread-local would make the unfiltered call compile and then silently work in a test where the thread-local happened to be set.

**The internal pipeline ports stay separate.** `SubscriptionRepositoryPort`, the relay's claim query and the worker's outcome writes are cross-tenant by design (ADR-002 sections 2.1, 2.2) and take no `TenantId`. They are separate interfaces from the client-facing ones, which is Interface Segregation doing real work here: the split is not stylistic, it is the boundary between "acts for one tenant" and "acts for the platform", and it is visible in the type system.

#### 5.3 Layer 3 - the tenant is bound into the SQL, and into the database session

The JDBC adapter binds the `TenantId` as a named parameter in the `WHERE` clause of every tenant-scoped query (`AND client_id = :client_id`), always bound, never concatenated (A05).

In addition, at the start of every transaction opened by a client-API use case, the adapter issues `SET LOCAL app.client_id = <value>` on the connection, from the same `TenantId` the port received. `SET LOCAL` is transaction-scoped, so the value is discarded at commit or rollback and cannot leak to the next borrower of a pooled connection. This is what layer 4 reads.

Implementation shape (a detail for the backend and DBA tasks, not a decision to relitigate): a small `TenantSessionBinder` collaborator invoked by the tenant-scoped persistence adapters, rather than an AOP interceptor, so the binding is visible in the call path and cannot be silently disabled by an annotation being forgotten. A transaction that never binds is not a security hole, because layer 4 fails closed.

#### 5.4 Layer 4 - PostgreSQL Row Level Security, fail-closed

RLS is enabled on the tenant-owned tables (`notification_events`, `deliveries`, `subscriptions`, and `delivery_attempts` via its `delivery_id` join to `deliveries`), with a policy of the shape:

> `USING (client_id = current_setting('app.client_id', true))`

Exact DDL, the `delivery_attempts` policy shape (which has no `client_id` column of its own and must therefore be expressed against its parent), and the interaction with ADR-003 section 3's monthly range partitioning are the DBA's call in the feature breakdown. Two properties are not the DBA's call and are fixed here:

- **It fails closed.** `current_setting('app.client_id', true)` returns `NULL` when the variable was never set, and `client_id = NULL` matches no row. A query that skipped layer 3 returns zero rows, never another tenant's rows. There is no configuration in which "forgot to set the tenant" degrades to "sees everything" (A10: the error path fails closed by construction rather than by an exception handler that someone has to write correctly).
- **Two database roles, and the API's role must not bypass RLS.** RLS does not apply to a superuser, to a role with `BYPASSRLS`, or to the table owner unless `FORCE ROW LEVEL SECURITY` is set. So:

| Role | Used by | RLS |
| --- | --- | --- |
| `challenge_api` | The client API request path | Subject to policies. Not the table owner, no `BYPASSRLS`. |
| `challenge_pipeline` | Ingest, relay, worker, DLQ consumer, all cross-tenant by design | Not subject to policies (either `BYPASSRLS` or excluded by policy), because it legitimately operates across every tenant. |

This means two connection pools against the same database, one per role. That is the real cost of I-C and it is accepted: the alternative, one role plus `SET LOCAL ROLE` switching per transaction, puts the security boundary on a statement that has to run correctly every time, which is the class of guarantee this whole section exists to eliminate. Two pools make the boundary a wiring decision made once at startup. The migration owning these roles and their grants belongs to the DBA task, and `FORCE ROW LEVEL SECURITY` on the tables is required if the owner and the pipeline role are ever the same principal.

**A test that runs as the table owner proves nothing.** The Testcontainers fixture must create both roles and run tenant-isolation tests through the `challenge_api` role, or every policy is silently bypassed and the suite is green for the wrong reason. This is called out explicitly because it is the most likely way this design is implemented and verified incorrectly, and it belongs in the acceptance criteria of the DBA and backend tasks.

#### 5.5 Cross-tenant access returns 404, never 403

A foreign resource is indistinguishable from a nonexistent one. This is not an exception-mapping preference, it falls out of the design: the row is filtered out at the query, so the use case receives `Optional.empty()` and has no way to know whether the id exists elsewhere. There is no 403 branch to write, because there is no code path that has ever seen the foreign row.

| Situation | Response |
| --- | --- |
| No token, malformed token, bad signature, expired, wrong `iss`/`aud`, missing `client_id` | 401, identical body in every case |
| Valid token, missing the required scope | 403 |
| Valid token and scope, id belongs to another tenant | **404**, identical to a nonexistent id |
| Valid token and scope, id does not exist | 404 |
| Valid token and scope, replay target is not `DEAD`, or a live row already exists for the pair | 409 (ADR-005 section 1) |

The 409 case is worth a note, because it is the one place where a status code could leak: 409 is only ever reachable for a delivery the caller already owns, since a foreign id is 404 long before the state check runs. Order matters, and the use case must resolve the row tenant-scoped first and check state second.

**Response bodies carry no cross-tenant information.** A 404 body says the resource was not found and nothing else. Error responses never echo the `client_id`, never name the tenant that does own a row, and never vary in length or timing in a way that distinguishes the two 404 cases in any way that is cheap to avoid.

### 6. Rate limiting placement

**Both layers, with different jobs. Neither replaces the other.**

| Layer | Owns | Why it must be there |
| --- | --- | --- |
| Edge (API gateway or WAF, out of this service's code) | Volumetric abuse: per-IP and per-connection floods, malformed-request storms, unauthenticated traffic, L7 DDoS | It is the only layer that can drop traffic before it costs this service a thread, a JWT verification and a database connection. An application-layer limiter that has already parsed and verified the request has already paid most of the cost it is trying to avoid. Unauthenticated floods in particular must never reach the JVM. |
| Application (`adapter/in/web`, this service) | Per-client, per-scope budgets: reads versus replay | The edge does not know who the caller is. The tenant is a verified claim inside a signed token; a gateway that has not verified the signature cannot safely key a budget on `client_id`, and one that has is duplicating this service's token contract. Semantic budgets need the authenticated principal, which only exists after authentication. |

This is why the rate-limit filter sits **after** the bearer-token filter in the chain (section 2, step 7). Placing it earlier would mean keying on something unauthenticated, which means an attacker chooses their own bucket.

**Budgets (proposals, not derived from measured usage - the same caveat class as ADR-004's Q5 and ADR-006's Q7):**

| Bucket | Limit | Reasoning |
| --- | --- | --- |
| Read, per `client_id` | 600 requests per minute, burst 60 | Polling delivery status is a legitimate high-frequency pattern; the bounded page size and default 30-day window (ADR-005 section 1) already cap per-request cost, so the limit protects against loops rather than against expense. |
| Replay, per `client_id` | 10 per minute and 200 per day, burst 5 | Replay is a write that generates outbound traffic to the client's own endpoint. It is the one endpoint where an unbounded client can turn this platform into a load generator aimed at themselves, and it is the one the self-service API's A07 authentication exposure specifically calls out for rate limiting. Roughly two orders of magnitude tighter than reads, deliberately. |
| Unauthenticated | Edge only | Nothing authenticated-adjacent is budgeted in-process; a request without a valid token is rejected at the filter and is the edge's problem. |

**Behavior on exhaustion.** 429 with a `Retry-After` header and an RFC 9457 problem-detail body. The limiter is a filter, so the request never reaches a use case, a database connection or the pipeline. A 429 from this service is not recorded as a delivery event of any kind.

**Implementation shape and its honest limitation.** In-process token buckets keyed by `(client_id, bucket)`, with bounded-size eviction so the key space cannot grow without limit (the tenant count is not known to this service and an unbounded map keyed by a claim is itself a denial-of-service vector). The buckets are **per pod**, so with N instances a client's effective budget is N times the configured number. This is the same bounded over-count that ADR-006 section 1.2 already accepts for the circuit breaker's pre-trip window, accepted here for the same reason: the alternative is Redis or a database round trip on every request, which is new infrastructure and new latency on the hot path to make an approximate control exact. The edge layer is where a globally exact limit belongs if one is ever genuinely needed. The per-pod divisor should be applied when configuring the numbers, and the effective global budget is what gets documented to clients.

**A03 note.** A token-bucket library (Bucket4j or equivalent) is a new dependency, which is exactly the category ADR-001's A03 row flags. Pinned version, dependency scanning in the build, and a conscious check that it introduces no transitive runtime surface beyond in-memory buckets. Writing the bucket by hand is also viable at this scale and avoids the dependency; the trade is a small amount of code against a supply-chain entry, and it is a backend-task decision, not an architectural one.

**Not rate limiting, but adjacent and already handled.** The `Idempotency-Key` header on replay (ADR-005 section 1) and the partial unique index on `(event_id, subscription_id)` filtered to non-terminal statuses (ADR-003 sections 2 and 3) already prevent a double click from producing two live replays. Rate limiting is about volume; those are about correctness. Both are needed and neither substitutes for the other.

### 7. RSA public key per environment

**The governing rule: the local development key is not a lesser-quality production key, it is a different mechanism entirely, and the two must not be expressible in the same configuration.**

| Environment | Mechanism | Configuration |
| --- | --- | --- |
| Production and staging | JWKS from the IdP, `kid`-matched, rotating | `spring.security.oauth2.resourceserver.jwt.issuer-uri` set to the corporate IdP realm. **No public-key property is set, and setting one is a startup failure.** |
| Local development and demo | A single static RSA public key | `spring.security.oauth2.resourceserver.jwt.public-key-location` pointing at a PEM, active only under the `local` profile. |
| Tests | The same static key as local, from the test classpath | Configured by the test slice, alongside `TestcontainersConfiguration`. |

**Where the key material lives, and why it cannot ship.**

- The dev key pair lives in `tools/dev-jwt/` at the repository root: **outside `src/main/resources`**, therefore outside the built jar and outside the container image. This is the structural half of the guarantee. A key that is not in the artifact cannot be loaded by the artifact, regardless of what any profile says. Putting it in `src/main/resources` with a profile guard would mean the production artifact contains a key that only a configuration mistake stands between and being trusted.
- The **private** key in `tools/dev-jwt/` is committed deliberately and is documented, in the file itself and in the script's output, as a publicly known test key with no security value. This is the user's stated requirement (a helper script in the repo that signs test tokens) and it is safe precisely because the key is worthless: it is never valid anywhere but a developer laptop, because production accepts only JWKS-resolved keys from the IdP issuer.
- The helper script (`tools/dev-jwt/issue-token.sh` or equivalent) mints a token for a given `client_id` and scope set, signed with that key, matching section 3's claim contract exactly. It exists so that the local and demo experience needs no IdP, and so that the token contract has an executable reference implementation that fails loudly if the two drift.
- Secrets scanning in the build must be configured with an explicit allow for this one path, or it will flag a committed private key on every run and be ignored, which is worse than not having it. That is a devops task detail and is stated so it does not become an unexplained alarm.

**Fail-safe on misconfiguration, checked at startup.** A `SecurityConfigurationValidator` bean runs during context initialization and **fails the application start** (A02, A10 - fail closed, loudly, before serving one request) if any of the following hold:

1. The active profile is not `local` or `test`, and `jwt.public-key-location` is set. A static key in production is the exact failure this section exists to prevent, and it is a startup failure rather than a log warning because a warning in a healthy-looking boot log is not a control.
2. The active profile is not `local` or `test`, and `jwt.issuer-uri` is absent or blank.
3. `jwt.issuer-uri` is set, in a non-local profile, to a loopback, private or link-local host. A production service pointing its trust anchor at `localhost` is either a copy-paste error or an attack, and neither should boot.
4. The configured audience is absent in any profile. An audience-less resource server accepts any token from the issuer (section 3).
5. The resolved RSA key is smaller than 2048 bits.
6. The `local` profile is active and the build is a release artifact (detectable via a build-stamped property). A production image starting in the local profile is itself the misconfiguration.

There is deliberately no fallback, no default key, and no "if JWKS is unavailable, use the configured static key" path. A resource server that degrades to a weaker trust anchor when its strong one is unreachable has turned a transient IdP outage into a permanent authentication bypass. If JWKS cannot be reached, requests fail 401 and the service stays up to serve health probes and the internal pipeline, which does not depend on client tokens at all. That asymmetry is deliberate: an IdP outage stops clients reading their history, and does not stop notifications being delivered.

**Rotation.** Production rotation is the IdP's, handled by JWKS `kid` selection with no change or restart here. The local key does not rotate; it is regenerated by the script when someone wants a new one, which is a developer convenience with no operational meaning.

### 8. What this ADR does not decide

Stated explicitly so the boundaries are not inferred:

- **SSRF and egress controls** for the outbound webhook call (ADR-002's A01-SSRF row). Different direction, different mechanism, its own follow-up.
- **Producer authentication** for the ingest endpoint beyond reserving its path and its own filter chain. ADR-002's Q10 fixes the mechanism (IAM SigV4); the verification wiring is a security-engineer task.
- **Webhook payload signing** (ADR-004 section 2) and the secret rotation window. Inbound authentication and outbound signing share no machinery.
- **Subscription management authorization**, which does not exist as an API (Q9, `docs/architecture-overview.md`). When it does, `notifications:write` or similar is a third scope, and its tenant scoping rides layers 1 through 4 unchanged, which is the point of putting the enforcement in the structure rather than in each endpoint.
- **The IdP's own configuration**: which clients exist, how they obtain tokens, token lifetimes at issuance, and scope grants. Out of scope by the user's framing.

## Consequences

**Becomes easier**

- Adding an endpoint is safe by default: an unlisted path is denied by the terminal chain, and a tenant-scoped query cannot be written without a `TenantId` because no such port method exists.
- Tenant isolation becomes testable as a property rather than per endpoint: one integration test asserting that tenant B receives 404 for tenant A's id, plus one asserting that a deliberately unfiltered raw query under the `challenge_api` role returns zero rows, covers the mechanism for every current and future endpoint.
- Local development and the demo need no IdP, no container and no network: a script, a key file and a profile.
- The domain stays framework-free. `TenantId` is a record; nothing in `domain/` or `application/` imports Spring Security, and an ArchUnit rule keeps it that way.
- Scope separation means a leaked read token cannot trigger outbound traffic.

**Becomes harder / debt created**

- **Two database roles and two connection pools.** Real operational cost: two sets of credentials, two pool configurations, and a new way to be wrong (the API path accidentally wired to the pipeline pool silently disables RLS). Mitigation is a startup assertion that the API pool's role cannot bypass RLS, which is cheap and should be in the backend task.
- **RLS is a new debugging surface.** "The query returns nothing" now has an additional cause that does not appear in the SQL being read. Worth a runbook line.
- **The Testcontainers fixture grows**: both roles and the policies must exist for isolation tests to mean anything, and a test running as the owner passes for the wrong reason.
- **Per-pod rate limits are approximate.** The documented client budget must account for the instance count, and it drifts when the deployment scales.
- **Revocation is bounded by token lifetime**, the accepted cost of T-B.
- **The scope names become a public contract** with the corporate IdP. Renaming them later is a coordinated change across two systems.

**Blocks / unblocks**

- Unblocks: the backend task for the security configuration and the `TenantId` mechanism, the DBA task for RLS policies and the two-role migration, the devops task for per-environment key configuration and the secrets-scanning allow-list, and the security-engineer review of the whole chain.
- Blocked by: **ADR-001 through ADR-006** — no longer blocking; all seven ADRs (including this one) are now `Accepted`, so the feature/task breakdown can proceed.
- Follow-up ADRs or tasks likely needed for: SSRF/egress controls, producer SigV4 verification, webhook signing and secret rotation, and subscription-management authorization when that API exists.

## OWASP / Security Impact

| OWASP Top 10:2025 | How this decision addresses it |
| --- | --- |
| **A01 Broken Access Control (IDOR)** | The primary subject of this ADR. Four independent layers (sections 5.1 to 5.4): a tenant type constructible only from the principal, port signatures with no unfiltered overload, bound SQL predicates, and fail-closed PostgreSQL RLS under a non-bypassing role. Cross-tenant access returns 404, not 403, so resource existence does not leak (section 5.5). The tenant is never read from a path variable, query parameter or header, enforced by an ArchUnit rule rather than by convention. |
| **A01 Broken Access Control (SSRF)** | Not addressed here. Outbound direction, explicitly deferred (section 8). |
| **A02 Security Misconfiguration** | Deny-by-default terminal chain plus deny-by-default inside the API chain (section 2). Actuator split so only health probes are unauthenticated and health details are hidden (section 2). Startup validator that refuses to boot on six specific misconfigurations, including a static key outside the local profile and a missing audience (section 7). No fallback trust anchor. |
| **A03 Software Supply Chain Failures** | One new runtime dependency is introduced at most, a token-bucket library, and hand-rolling it is a viable alternative (section 6). Pinned version and dependency scanning, consistent with ADR-001's A03 row. The committed dev key requires a deliberate, documented secrets-scanner allow-list so the scanner stays credible. |
| **A04 Cryptographic Failures** | RS256 only, enforced with an explicit single-algorithm allow-list so `alg: none` and HMAC algorithm confusion are rejected before verification. Minimum 2048-bit keys, checked at startup. JWKS with `kid` matching in production; the local static key is structurally incapable of shipping because it lives outside the artifact (section 7). |
| **A05 Injection** | `client_id` from the token is format-validated at the boundary (section 3) and always a bound parameter in SQL, never concatenated (section 5.3). The format check is specifically load-bearing for the `SET LOCAL app.client_id` path, where the value reaches a session-configuration statement. Filter parameters remain bound and enum-validated per ADR-005's A05 row. |
| **A06 Insecure Design** | The design premise of section 5: a rule that depends on a developer remembering it is not a control. The enforcement is in the type system and in the database, chosen so the correct path is also the easiest path, since an enforced path that is inconvenient gets routed around. |
| **A07 Authentication Failures** | Every endpoint authenticated, no permit-all outside health probes. Required claims with explicit issuer, audience, expiry, maximum-lifetime and 30-second skew validation. Stateless, no session fixation surface. Identical 401 responses for every distinct validation failure, so failure reasons are not enumerable. |
| **A09 Logging & Alerting Failures** | Authentication failures, authorization denials, and rate-limit rejections are logged structurally with `client_id`, `sub`, trace id and outcome. **The bearer token, its signature, and any key material are never logged**, at any level, consistent with ADR-002's A09 row on signature headers. Worth alerting on: a sustained 401 rate from one source, any 403 on the replay scope (a client attempting an operation it was never granted is a signal), and rate-limit exhaustion by client. |
| **A10 Mishandling of Exceptional Conditions** | Every error path in this design fails closed. An unset tenant session variable matches zero rows rather than all rows. An unreachable JWKS produces 401s rather than a fallback to a weaker key. A misconfigured key or issuer prevents startup rather than degrading silently. An unmatched path is denied rather than defaulting to the last chain's rules. |

## Downstream

Once **this ADR and ADR-001 through ADR-006 are all Accepted**, the feature/task breakdown for this ADR lives at `docs/features/FEAT-002-api-security-and-tenant-isolation/`.

Anticipated task split, for sizing only, not a commitment before approval: the `TenantId` type and its resolver; the filter chain configuration and the startup validator; the JWT decoder, validators and authority mapper; the RLS migration and the two-role setup (DBA); the tenant-scoped port signature changes and their adapters; the rate-limit filter; the ArchUnit rules; the dev-key tooling and per-environment configuration (devops); and an end-to-end cross-tenant isolation test run through the non-bypassing database role.
