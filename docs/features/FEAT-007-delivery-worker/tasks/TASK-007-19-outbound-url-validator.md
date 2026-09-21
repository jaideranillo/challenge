---
id: TASK-007-19
feature: FEAT-007
title: OutboundUrlValidator — application-level SSRF validation at send time
status: Ready for Review
agent: security-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-19: `OutboundUrlValidator`

## Open addenda on this task

The base scope of TASK-007-19 is `Ready for Review`. 19a and 19b below are **implemented,
Ready for Review**; the wiring note is recorded as already landed. A reviewer should read the
frontmatter `status` as covering the base scope plus 19a/19b.

## Follow-up 19a (2026-09-21, Tech Lead decision): the allowlist waives HTTPS too, under the `local` profile

**Status: Ready for Review, implemented by `security-engineer`.**

### The problem, as found

TASK-007-20's implementation surfaced it: the allowlist waives the private-range check (rule 3)
but never the HTTPS requirement (rule 1), and the local stub receiver is plain HTTP with no
`server.ssl` anywhere. So a local demo attempt hits `POLICY_REJECTED: scheme must be https` no
matter what the allowlist says. The exception exists and does nothing.

### The decision

**No TLS for the local stub — that is overkill for local dev, and the Tech Lead ruled it out.**
Instead, an allowlist entry now waives **both** rule 1 (HTTPS) and rule 3 (private/reserved
ranges), and the waiver applies only when **both** of these hold:

1. the host is in `challenge.egress.allowed-hosts`, **and**
2. the active Spring profile is `local`.

**Every other profile keeps HTTPS mandatory, with no exceptions** — identical to today's
behavior. An allowlist entry that somehow appeared in a non-local profile would waive nothing:
the profile check is evaluated independently of the list.

This supersedes an earlier draft of this addendum that used a second boolean property plus a
startup guard. The profile is the better second condition: it is a first-class deployment
concept rather than one more line in a YAML file, it cannot be set by editing
`application.yaml`, and it needs no guard bean to police a flag that no longer exists.

For the record, since it should not be revisited: terminating TLS in front of the stub was
rejected because a self-signed certificate is not trusted by the JDK `HttpClient`, so it would
require either a committed private key (A04, permanent secret-scanner noise) or a
profile-specific `SSLContext` on the webhook client — relaxing the very TLS path ADR-004 §2
protects, which is a worse place for the exception than a scheme check, with certificate expiry
as a recurring way for the demo to break.

### The change

- **Rule 1 (HTTPS) is skipped for an allowlisted host under the `local` profile**, exactly as
  rule 3 already is. One shared "is this host waived" predicate governs both; do not write two
  independent checks that could drift apart.
- The validator learns whether the profile is active by resolving it **once at construction**
  (`Environment.acceptsProfiles(Profiles.of("local"))` into a `final boolean`), never per call.
  A Spring type in an `adapter/out` class is fine; per-call environment lookups are not.
- **Update the class javadoc.** It currently says HTTPS is enforced unconditionally, which is no
  longer true. State the waiver and both of its conditions in one line.
- Keep the waiver out of the reason strings: a rejection must never hint that a profile or a
  list entry would have allowed it.

| Profile | Host on allowlist | `http://localhost/hook` |
|---|---|---|
| default | no | `POLICY_REJECTED` |
| default | yes | `POLICY_REJECTED` — the profile condition fails |
| `local` | no | `POLICY_REJECTED` — the list condition fails |
| `local` | yes | `ALLOWED` |

### Acceptance criteria for 19a

- [x] One predicate decides "waived", and both rule 1 and rule 3 consult it.
- [x] All four rows of the table above are unit-tested.
- [x] The **existing** scenario 1 test — under the default profile, `localhost`, the private
      ranges and `169.254.169.254` are all rejected — passes **unchanged**. It is the guard that
      this waiver never reaches production; do not edit it.
- [x] **New test:** with the `local` profile active and `localhost` on the allowlist,
      `http://localhost:<port>/hook` is `ALLOWED`.
- [x] A host not on the allowlist is still `POLICY_REJECTED` for `http://` under `local`.
- [x] Profile resolution happens once, at construction, not per `validate` call.
- [x] The class javadoc no longer claims HTTPS is unconditional.
- [x] No reason string mentions the profile or the allowlist.
- [x] No second boolean property is introduced — the profile is the whole of the second condition.

**Implementation note:** the public constructor now takes `(EgressProperties, Environment)`
and resolves `Environment.acceptsProfiles(Profiles.of("local"))` once into a `final boolean
localProfileActive`, consumed by a package-private test constructor
`(EgressProperties, boolean localProfileActive, Function<String, InetAddress[]> resolver)` so
tests need no Spring `Environment`. TASK-007-20's wiring must supply the `Environment` bean
(Spring provides one automatically) — no other change to the constructor's public shape is
expected.

## Follow-up 19b (2026-09-21): `EgressVerdict` carries the resolved address

**Status: Ready for Review, implemented by `security-engineer`.** Raised by TASK-007-20's implementation: the
warn-level security log can only name the target host, because the verdict carries `state` plus a
generic reason and nothing else. Host alone does not distinguish "resolved to `169.254.169.254`"
from "resolved to `10.0.0.5`" from "scheme was wrong", and that distinction is the entire
operational value of the alert — the first is someone reaching for cloud credentials.

`EgressVerdict` gains `Optional<String> resolvedAddress`, populated on `POLICY_REJECTED` with the
literal address that triggered the rejection (the **first offending** address when several
resolved), and empty for a scheme rejection or a DNS failure, where no address exists. Keep it a
`String` rather than an `InetAddress` so the verdict stays a plain data carrier and nothing
downstream is tempted to re-resolve it.

### Acceptance criteria for 19b

- [x] `resolvedAddress` is present on a range-based `POLICY_REJECTED`, empty on a scheme
      rejection and on `DNS_FAILURE`.
- [x] When several addresses resolve and one is blocked, the blocked one is the one carried.
- [x] Unit tests cover all three cases.
- [x] The field is not persisted anywhere and is not added to any port signature.

## Note: Spring wiring landed here (2026-09-21)

TASK-007-20's implementer added `@Component` and
`@EnableConfigurationProperties(EgressProperties.class)` to `OutboundUrlValidator`. That is this
task's file, and this task left the class unannotated, so the validator would never have been a
bean and the wiring could not work at all. **Recorded as a correct fix, not scope creep**, and
this task's acceptance criteria are read as including it: the validator is a Spring-managed bean
and `EgressProperties` is bound. Do not revert it, and do not add a second
`@EnableConfigurationProperties` for the same type elsewhere.

## Feature

FEAT-007

## Assigned Agent

`security-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/OutboundUrlValidator.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/dto/EgressVerdict.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/config/EgressProperties.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/webhook/OutboundUrlValidatorTest.java` (new)
- Concern: decide, for one URL, whether this service may call it. Pure decision logic plus DNS
  resolution — no HTTP call, no wiring (TASK-007-20 wires it in). Three production files and its
  test: one concern, and the verdict type is a four-line record kept in its own file per the
  repo's package-by-kind convention.

## Why this is no longer deferred

ADR-002's A01 row asks for HTTPS only, public DNS resolution only, denial of
RFC1918/loopback/link-local/metadata ranges, no redirects, and resolve-then-pin. **Tech Lead
direction of 2026-09-21 splits that row in two:**

- **Network-level egress controls** (security groups, a controlled egress path) stay deferred.
  They are infrastructure, they do not apply to a local run, and they are not this task.
- **Application-level validation is code, is cheap, and ships now** — this task.

## The rules

```java
/** Decides whether this service may call the target, resolving DNS on every call. */
EgressVerdict validate(String targetUrl);
```

### The verdict distinguishes two failures that mean opposite things

`EgressVerdict` (record or sealed type in `adapter/out/webhook/dto`) carries one of three states
plus a short reason string:

| State | When | How the caller classifies it (TASK-007-20) |
|---|---|---|
| `ALLOWED` | scheme and every resolved address pass | proceed to the POST |
| `DNS_FAILURE` | timeout, NXDOMAIN, resolver error — **no** answer obtained | `AttemptOutcome.RETRYABLE`, breaker-counting, exactly as ADR-004 §1 already classifies a DNS failure |
| `POLICY_REJECTED` | an answer **was** obtained and the target is forbidden — private/reserved range, `169.254.169.254`, non-HTTPS scheme | `AttemptOutcome.NON_RETRYABLE` -> `DEAD`, **not** breaker-counting, plus a security signal |

**Do not collapse these into one "rejected" state.** A DNS failure is transient and may resolve
itself; a target that resolved cleanly to `10.0.0.5` will resolve there again on every retry, so
retrying is pure waste, and it is evidence the *configuration* is wrong rather than evidence the
client's endpoint is unhealthy. That distinction is exactly why the second one must not touch the
breaker's failure count.

`NON_RETRYABLE` is the **existing** `AttemptOutcome` value: it already terminates in `DEAD`
(ADR-004 §1) and already returns `false` from `countsTowardCircuitBreaker()`. **No new
`AttemptOutcome` value, no new `TransportFailure` value, no enum or column change of any kind is
in scope, and adding one is a defect.**

The reason string is short, non-PII and safe to persist as `delivery_attempts.error` (e.g.
`"egress: target resolves to private range"`). It must not contain the secret, a header or the
event content; the host and resolved address are acceptable and useful here.

1. **HTTPS only.** Any other scheme is rejected, `http://` included.
2. **Resolve the host** with `InetAddress.getAllByName`, then reject if **any** resolved address
   falls in a blocked range. Checking only the first address is a hole: a hostname can resolve to
   a public and a private address at once.
3. **Blocked ranges**, checked against the resolved `InetAddress`, not against the literal text of
   the hostname:
   - loopback (`127.0.0.0/8`, `::1`)
   - any-local / wildcard (`0.0.0.0`, `::`)
   - private / site-local (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`)
   - link-local (`169.254.0.0/16`, `fe80::/10`) — this covers **`169.254.169.254`**, the cloud
     metadata endpoint, which must also be listed explicitly in the tests
   - multicast, and IPv4-mapped IPv6 forms of any of the above (unwrap before deciding, or a
     `::ffff:127.0.0.1` walks straight through)
4. **Validate at send time, on every attempt** — never once at subscription creation. A host that
   resolved publicly yesterday can resolve to `127.0.0.1` today; that is DNS rebinding, and
   per-attempt validation is the whole defense against it. Say so in the class's one-line javadoc.
5. **A DNS failure is never an allow.** Fail closed (A10) — but return `DNS_FAILURE`, not
   `POLICY_REJECTED`, so the caller retries rather than killing the delivery.
6. **The local-profile allowlist.** `EgressProperties`
   (`@ConfigurationProperties("challenge.egress")`) carries `List<String> allowedHosts`,
   **empty by default**. A host on that list skips rule 3 (but never rule 1's HTTPS requirement
   unless the list entry is also explicitly marked, see below). This exists for exactly one
   reason: the stub webhook receiver used by the local demo runs on `localhost`, which rule 3
   would otherwise block, breaking the demo.
   - The list is populated **only** in `application-local.yaml` (TASK-007-20 does that).
     `application.yaml` must not contain a `challenge.egress.allowed-hosts` key at all — not an
     empty list with a comment inviting someone to fill it in, and not a commented-out example.
   - Because the stub is plain HTTP on localhost, decide and document one of: the allowlist entry
     also waives the HTTPS rule, or the local stub is served over HTTPS. Pick the narrower option
     you can make work, and state which in your handover.
   - **This allowlist is the exact mechanism by which a dev exception leaks into production.**
     The default-profile rejection test below is what prevents it, so write that test first.

## Out of Scope

- Wiring the validator into `JdkWebhookClientAdapter` and the local-profile configuration —
  TASK-007-20, deliberately split so each task stays at three files.
- Redirect handling. The adapter already sets `Redirect.NEVER` (TASK-007-06); do not duplicate.
- Network-level egress controls, security groups, proxies.
- Subscription-creation-time validation and ADR-005 §2's verification handshake.
- Any change to `WebhookClientPort` or its DTOs.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Keep DNS resolution injectable (a `Function<String, InetAddress[]>` defaulting to
`InetAddress::getAllByName`) so the range tests need no network.

Required unit scenarios — the first is the one that stops the dev exception reaching production:

1. **Under the default (non-local) configuration — an `EgressProperties` with an empty
   allowlist — every one of these is rejected:** `https://localhost/hook`,
   `https://127.0.0.1/hook`, `https://[::1]/hook`, `https://10.0.0.5/hook`,
   `https://172.16.0.1/hook`, `https://192.168.1.10/hook`, **`https://169.254.169.254/latest/meta-data/`**,
   and a hostname that resolves to any of those.
2. `http://example.com/hook` is rejected for scheme alone, before any resolution.
3. A hostname resolving to both a public and a private address is `POLICY_REJECTED`.
4. An IPv4-mapped IPv6 address (`::ffff:127.0.0.1`) is `POLICY_REJECTED`.
5. **A DNS failure returns `DNS_FAILURE`, never `POLICY_REJECTED`**, carries a reason, and does
   not throw — the distinction the whole verdict type exists for.
6. A public `https://` host resolving only to public addresses is `ALLOWED`.
7. With the host on the allowlist, the same localhost URL that scenario 1 rejects is `ALLOWED` —
   and a *different* private host, not on the list, is still `POLICY_REJECTED`.
8. Resolution happens on **every** call: two calls with the same URL both resolve (assert with a
   counting resolver), proving no per-subscription caching that would defeat rebinding defense.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] The verdict distinguishes `ALLOWED`, `DNS_FAILURE` and `POLICY_REJECTED`; the two failure
      states are never collapsed into one.
- [ ] No `AttemptOutcome` value, `TransportFailure` value, enum or column is added anywhere.
- [ ] Every reason string is short, non-PII and safe to persist as `delivery_attempts.error`.
- [ ] HTTPS-only, enforced before resolution, and reported as `POLICY_REJECTED`.
- [ ] Every resolved address is checked; loopback, any-local, private, link-local (including
      `169.254.169.254`), multicast and IPv4-mapped forms are all rejected.
- [ ] Validation is per call, with no caching of a previous verdict for a host or subscription.
- [ ] A DNS failure fails closed.
- [ ] `challenge.egress.allowed-hosts` defaults to empty and appears in **no** file other than
      `application-local.yaml` (which TASK-007-20 writes).
- [ ] The resolver is injectable so tests need no network.
- [ ] All eight unit scenarios exist, with scenario 1 written as an explicit
      default-profile-rejects-everything test.
- [ ] Nothing here logs the target URL at info or above.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] The handover states which option was chosen for the local stub's HTTP-vs-HTTPS problem.

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
