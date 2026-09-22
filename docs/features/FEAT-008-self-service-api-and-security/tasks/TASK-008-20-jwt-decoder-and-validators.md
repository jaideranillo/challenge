---
id: TASK-008-20
feature: FEAT-008
title: JWT decoder, claim validators and authority mapping
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-01, TASK-008-03, TASK-008-05]
date: 2026-09-21
---

# TASK-008-20: JWT Decoder, Validators and Authority Mapping

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/config/JwtDecoderConfig.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/JwtClaimValidators.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/config/JwtProperties.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/JwtClaimValidatorsTest.java` (new)
- Concern: what makes a token acceptable. No filter chain (TASK-008-21), no error body
  (TASK-008-23).

## The token contract (ADR-007 §3) — implement exactly this table

**Signature**

| Property | Rule |
|---|---|
| Algorithm | **`RS256` only**, as an explicit single-algorithm allow-list on the decoder, so `alg: none` and every HMAC algorithm are rejected **before** signature verification. This is the algorithm-confusion break (a token signed HS256 using the RSA public key as the HMAC secret) and the allow-list is the mitigation. A decoder that accepts the library default set is a defect |
| Key, production | JWKS from `issuer-uri` discovery, `kid`-matched, default refresh |
| Key, local/test | the single static RSA public key of TASK-008-02/-03, no `kid` matching |

**Claims**

| Claim | Validation |
|---|---|
| `iss` | equals the configured issuer exactly |
| `aud` | contains this service's configured audience. Without it, a token minted for any other service in the same realm is accepted here — a lateral-movement path, not a formality |
| `exp` | standard expiry with **30 seconds** of clock skew — not Spring's 60-second default |
| `iat` | present, not in the future beyond the skew |
| `nbf` | honored if present |
| `sub` | required; logged for audit; **never** used for authorization |
| `client_id` | **required**, non-blank, matching `TenantId`'s format (1–64, `[A-Za-z0-9_-]`). Rejected at the **decoder**, with 401, before any handler runs — so no endpoint can ever see a principal without a tenant |
| `scope` / `scp` | space-delimited string or array; mapped to authorities |

**Maximum lifetime:** `exp - iat` must not exceed the configured ceiling (default 1 hour,
TASK-008-03's `challenge.security.jwt.max-lifetime`). A labelled proposal, enforced regardless of
what the IdP does.

**Authority mapping** (ADR-007 §4): a `JwtAuthenticationConverter` with a
`JwtGrantedAuthoritiesConverter` configured with an **empty authority prefix**, source claim
`scope`/`scp`, so the scope `notifications:replay` becomes the authority
`notifications:replay` **verbatim**. **No `SCOPE_` prefix** — what is written in the security
configuration must be exactly what is written in the token, so the prefix-mismatch class of bug
cannot occur. Unknown scopes map to authorities and are simply never referenced.

**No hierarchy.** No `RoleHierarchy` bean. `notifications:replay` does not imply
`notifications:read`.

**What must not be built** (ADR-007 §3, "what deliberately is not done"): no token introspection,
no revocation list, no per-request IdP call, no `jti`/nonce replay store. If a validator you are
writing needs state, stop — it is out of contract.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**

Writable now, and required: the validators tested directly against hand-built `Jwt` objects — one
test per rejection reason. Use TASK-008-02's script flags (wrong audience, expired, missing
`client_id`) only as a reference for the shapes; the unit tests need no real token.

`DEFERRED — Testcontainers`: any test that boots the context to prove the decoder bean resolves
JWKS or loads the PEM. Specify it, do not write it.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- The filter chains — TASK-008-21.
- The startup validator — TASK-008-22.
- Error response bodies — TASK-008-23.
- Rate limiting — TASK-008-24.
- Anything on `/internal/**`. Producer SigV4 is a separate follow-up (ADR-007 §8).

## Acceptance Criteria

- [ ] The decoder accepts **only** RS256, by explicit allow-list, and rejects `alg: none` and
      every HMAC algorithm before signature verification.
- [ ] Issuer, audience, `exp` with 30s skew, `iat`, optional `nbf`, required `sub`, required
      well-formed `client_id`, and the max-lifetime ceiling are all validated.
- [ ] `client_id` validation happens at the decoder, not in a controller or a use case.
- [ ] `client_id` format matches `TenantId`'s rule exactly — one rule, referenced, not a second
      regex that can drift. State in the handover how you avoided duplicating it.
- [ ] Authorities are mapped from `scope`/`scp` with an **empty** prefix; no `SCOPE_` appears
      anywhere in this feature.
- [ ] No `RoleHierarchy` bean exists.
- [ ] No introspection, revocation list, replay store or per-request network call is introduced.
- [ ] Unit tests cover, each as its own case: valid token accepted; wrong `iss`; wrong/missing
      `aud`; expired beyond skew; expired **within** skew (accepted); `iat` in the future;
      missing `client_id`; blank `client_id`; malformed `client_id`; `exp - iat` over the ceiling;
      `scope` as a string and as an array both mapping correctly.
- [ ] Nothing logs the token, its signature, a claim value beyond `sub`/`client_id`, or key
      material, at any level (A09).
- [ ] These classes live in `adapter/in/web/security`; nothing in `application/**` or `domain/**`
      is touched.
- [ ] **DEFERRED — Testcontainers:** a context-level test that the decoder bean loads the local
      PEM under the `local` profile.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A04, A07).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
