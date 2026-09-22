---
id: TASK-008-02
feature: FEAT-008
title: Dev JWT key pair and token-issuing script
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-008-02: Dev JWT Key Pair and Token Script

## Feature

FEAT-008

## Assigned Agent

`devops-engineer` — this task is only for this agent.

## Scope

- File(s), all new, all under `tools/dev-jwt/` at the repository root:
  - `tools/dev-jwt/dev-public.pem` and `tools/dev-jwt/dev-private.pem`
  - `tools/dev-jwt/issue-token.sh`
  - `tools/dev-jwt/README.md`
- Concern: a local, IdP-free way to mint a token that satisfies ADR-007 §3's contract.

## The governing structural rule (ADR-007 §7)

**The key pair lives outside `src/main/resources`, therefore outside the built jar and outside
any container image.** That is the guarantee, not a profile check: a key that is not in the
artifact cannot be loaded by the artifact. Putting it under `src/main/resources` with a profile
guard is explicitly rejected by the ADR and is a defect here.

The **private** key is committed deliberately. Both PEM files and the README must state, in
plain words, that this is a publicly known test key with no security value, valid nowhere but a
developer laptop, because production accepts only JWKS-resolved keys from the IdP issuer.

## What the script produces

`issue-token.sh` mints an RS256 JWT signed with `dev-private.pem`, matching ADR-007 §3 exactly:

| Claim | Value |
|---|---|
| `iss` | the local issuer string, matching what TASK-008-03 configures |
| `aud` | the service's audience identifier, matching what TASK-008-03 configures |
| `exp` | `iat` + lifetime, default 1 hour and **never** more than the 1-hour ceiling of ADR-007 §3 |
| `iat` | now |
| `sub` | the client id, or a supplied subject |
| `client_id` | **required**, supplied as an argument; the tenant |
| `scope` | space-delimited, supplied as an argument; `notifications:read`, `notifications:replay`, or both |

Interface: `issue-token.sh --client-id CLIENT001 --scope "notifications:read"`, printing the
token on stdout and nothing else, so it composes with `curl -H "Authorization: Bearer $(...)"`.
A missing `--client-id` is an error, not a default — a token without the claim is exactly what
ADR-007 §3 requires the decoder to reject, and the script must not be the place that makes a
malformed token easy to produce by accident.

The script must also be able to produce **deliberately invalid** tokens for the negative tests
that TASK-008-20 and TASK-008-28 write: at minimum a flag for a wrong audience, a flag for an
already-expired token, and a flag to omit `client_id`. Keep them as explicit, clearly named
flags — these exist to make the 401 path testable, and no flag may weaken the default output.

Bash plus `openssl` is fine, as is any other approach that needs no network and no IdP. Do not
add a runtime dependency to the application to support the script.

## Out of Scope

- Any change to `application.yaml` or `application-local.yaml` — TASK-008-03.
- Any test-classpath copy of the key — TASK-008-28 and TASK-008-29 read it from `tools/dev-jwt/`
  or place their own copy under `src/test/resources`, and that is their call, not this task's.
- Any JWKS endpoint, any IdP container, any addition to `compose.yaml`.
- Key rotation tooling. The local key does not rotate (ADR-007 §7).

## Acceptance Criteria

- [ ] Nothing this task creates lives under `src/main/resources` or is packaged into the jar.
- [ ] The RSA key is **at least 2048 bits** — TASK-008-22's startup validator fails the boot
      below that, so a smaller key would break local development.
- [ ] Both PEM files and the README state the key is a publicly known test key with no security
      value and is valid only locally.
- [ ] `issue-token.sh --client-id X --scope "notifications:read"` prints a single RS256 token and
      nothing else on stdout.
- [ ] The token's claims match ADR-007 §3's table exactly, including `aud` and `client_id`.
- [ ] A missing `--client-id` exits non-zero with a message; it never defaults.
- [ ] Flags exist for: wrong audience, expired token, omitted `client_id`. Each changes only the
      one thing it names.
- [ ] Default lifetime is 1 hour and the script cannot emit `exp - iat` greater than 1 hour.
- [ ] The script needs no network, no IdP and no container.
- [ ] The README says how to use the token against a locally running service in one copy-pasteable
      line, and states that no IdP is involved.
- [ ] No new OWASP Top 10:2025 exposure introduced: the committed private key is the ADR-007 §7
      decision, is documented as such in the file itself, and is worthless outside `local`/`test`.

## Definition of Done

Files written, script run once locally to confirm it emits a token. **Do not run `git add` or
`git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
