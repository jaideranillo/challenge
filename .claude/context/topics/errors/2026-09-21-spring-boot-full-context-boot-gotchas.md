# Spring Boot full-context boot gotchas (found via first real `bootRun`, not unit tests)

This project's phase rule (unit tests only, no Testcontainers/Spring context) means these classes
of bug are invisible until someone actually runs `./gradlew bootRun` against real infra. Found in
one session testing FEAT-008 end-to-end; several were pre-existing (FEAT-006) and had never
surfaced. Worth checking for on every feature that adds config/filter beans before assuming "unit
tests pass" means "boots correctly."

## `@ConfigurationProperties` record never bound because nothing `@EnableConfigurationProperties`'d it

A `@ConfigurationProperties("x.y.z") record Foo(...)` compiles and unit-tests fine (you just `new`
it directly), but at runtime Spring never binds/creates the bean unless something registers it —
either `@EnableConfigurationProperties(Foo.class)` on some `@Configuration`/`@Component`/`@Service`
class, or `@ConfigurationPropertiesScan`. A constructor parameter of that type fails at bean
creation with "No qualifying bean of type ... available." **Check**: for every new
`@ConfigurationProperties` class, grep for its name inside an `@EnableConfigurationProperties(...)`
somewhere in the same PR — five of these were missing in one session (`RetryProperties`,
`ApiDataSourceProperties`, `IdempotencyProperties`, `WorkerProperties`, and `JwtProperties` in an
earlier task).

## A `@Bean` with two constructors and no `@Autowired` fails at instantiation, not at compile time

A class with a production constructor and a test-seam constructor (e.g. "inject a fixed clock/
executor/queue-url for tests"), neither annotated, compiles fine and every unit test that calls the
test-seam constructor directly passes. Spring's container, given two eligible constructors and no
`@Autowired`/no no-arg constructor, throws `NoSuchMethodException: <init>()` at bean creation.
**Fix**: annotate exactly the production constructor `@Autowired`, leave the test-seam one bare.

## A `@Bean` of type `jakarta.servlet.Filter` gets auto-registered globally *in addition to* explicit chain placement

Spring Boot's `ServletContextInitializerBeans` registers **every** `Filter` bean as a global
servlet filter (all paths) automatically — independent of whether that same bean is also placed
into one specific `SecurityFilterChain` via `addFilterAfter`/`addFilterBefore`. Symptom: logic meant
to run only on one URL pattern (e.g. a per-client rate limiter reading a JWT claim) also runs on
every other path, including actuator health checks, and throws there because the precondition
(an authenticated JWT) doesn't hold. **Fix**: add a `FilterRegistrationBean<YourFilter>` bean with
`.setEnabled(false)` to suppress the automatic global registration, keeping only the explicit
chain-scoped one.

## A custom filter placed before `AnonymousAuthenticationFilter`/`ExceptionTranslationFilter` in a chain can't rely on `Authentication` being non-null, and its exceptions escape uncaught

Default Spring Security filter order: `BearerTokenAuthenticationFilter` runs *before*
`AnonymousAuthenticationFilter` and `ExceptionTranslationFilter`. A custom filter placed via
`addFilterAfter(filter, BearerTokenAuthenticationFilter.class)` therefore sees `null` authentication
for an unauthenticated request (not even an anonymous token yet), and if it throws
(`InsufficientAuthenticationException` or similar) that exception is *not* caught by
`ExceptionTranslationFilter` — it propagates raw out of the filter chain, and the servlet container's
default `/error` handling takes over instead of the app's configured `AuthenticationEntryPoint`.
**Fix**: place such filters with `addFilterBefore(filter, AuthorizationFilter.class)` instead —
that position is after both `AnonymousAuthenticationFilter` and `ExceptionTranslationFilter`, so
`Authentication` is always non-null and any exception thrown is properly translated to a 401/403.

## `oauth2ResourceServer().jwt(...)`'s `AuthenticationException` path is a *separate* entry point from `exceptionHandling().authenticationEntryPoint(...)`

`BearerTokenAuthenticationFilter` catches its own decode/validation failures (expired token, wrong
audience, malformed signature) and routes them to whatever entry point is configured on the
`oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` DSL itself. Configuring only
`exceptionHandling().authenticationEntryPoint(customEntryPoint)` does not cover this path — a
missing token gets the custom entry point (via `ExceptionTranslationFilter`/`AuthorizationFilter`)
but an invalid token gets Spring's raw default response. If a spec requires identical bodies for
every authentication failure (anti-oracle requirement), both paths must call
`.oauth2ResourceServer(oauth2 -> oauth2.jwt(...).authenticationEntryPoint(customEntryPoint))` **and**
`exceptionHandling().authenticationEntryPoint(customEntryPoint)`.

## A `Optional<Integer>`/primitive-`int` field bound from an absent HTTP query param throws at binding, not at business-logic validation

Spring's data binder can bind an absent request parameter to a `null`-capable field (`Integer`,
`Optional<T>`) with no error, but fails a primitive (`int`, `long`, `boolean`) with a
`MethodArgumentNotValidException`/type-mismatch that Spring dispatches to `/error` — which, if
`/error` isn't allow-listed on any security chain, comes back as a generic 403/500 with no relation
to the real cause. If a field represents "optional, apply a default downstream," it must be a boxed/
`Optional` type at the web-DTO boundary, with the actual default value resolved *before* constructing
whatever inner command/record enforces "must be positive" (constructing that command directly with
`0` just moves the same exception one layer down instead of fixing it).

## A committed PEM file's leading `#`-comment header (put there for the reader's benefit) breaks Spring Security's PEM parser

`RsaKeyConverters.x509()` (used to load a public key from a file for local/test JWT validation)
requires the resource to start exactly at `-----BEGIN PUBLIC KEY-----` — text before that line
(even an explanatory security-notice comment block) causes
`IllegalArgumentException: Key is not in PEM-encoded X.509 format`. Keep such warnings in an
adjacent README instead of inside the `.pem` file itself.

## `RestClient.Builder` is not always an injectable bean even with `spring-boot-starter-webmvc` present

Injecting `RestClient.Builder` as a constructor parameter (the documented pattern for getting a
pre-configured builder) failed with `UnsatisfiedDependencyException: No qualifying bean of type
'org.springframework.web.client.RestClient$Builder' available` in this project's dependency set
(no `RestClientAutoConfiguration` bean was present to satisfy it). **Fix**: construct it directly
— `RestClient.builder().baseUrl(...).build()` in the constructor body — instead of relying on DI
for it, when there's no other reason to need the shared/customized builder bean.

## Two `SecurityFilterChain` beans with overlapping matchers: the lower `@Order` value always wins, regardless of matcher specificity

`SecurityConfig`'s production chain (`@Order(2)`, matcher `/internal/**`, `anyRequest().authenticated()`)
and a new narrower local-only chain (matcher `/internal/events/**`, intended to permit-all under one
path) do **not** resolve by "most specific matcher wins" — `FilterChainProxy` tries chains in `@Order`
sequence and uses the first whose matcher matches. To make the narrower chain win, it needs a lower
`@Order` value than the broader one it's meant to override (used `@Order(0)`, ahead of the broad
chain's `@Order(2)`), not just a narrower path pattern.

## Local dev Postgres volume can drift: schema has a column Flyway's own history table doesn't know about

Symptom: `ERROR: column "x" of relation "y" already exists` on a migration that should be a no-op
because it already ran. Cause: the column was added to the running container's data volume at some
point without the corresponding `flyway_schema_history` row being recorded (e.g. an earlier partial
run, or manual intervention) — Flyway believes it's still at an earlier version and tries to reapply.
Fix for a disposable local dev volume: `docker compose down -v` and let the app recreate it from
scratch; check `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank`
first to confirm the mismatch before doing this on anything that isn't disposable dev data.
