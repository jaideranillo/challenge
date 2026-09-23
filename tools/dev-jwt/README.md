# Dev JWT tooling

Local-only tooling for FEAT-008 / ADR-007 (self-service API security). Lets you mint a valid
RS256 test JWT against this repository's `local` Spring profile with **no IdP, no network, and
no container**.

## The key pair

`dev-private.pem` and `dev-public.pem` are a **publicly known test key pair with no security
value**. They are committed deliberately (ADR-007 SS7) and are valid nowhere except a developer
laptop running this project's `local` profile. Production and staging never read these files;
they validate tokens only against JWKS served by the corporate IdP. Leaking this key changes
nothing, because nothing outside `local`/`test` trusts it.

Both files live outside `src/main/resources`, so they are never packaged into the built jar or
the container image. This is a structural guarantee, not a profile check: a key that is not in
the artifact cannot be loaded by the artifact.

## Minting a token

There is no shell script here anymore — mint a token over HTTP instead, against the running app
(`POST /local/dev-token`, local profile only, `adapter/in/web/local/devtoken`). It signs with the
same key pair above and produces the same claim shape:

```bash
curl -X POST http://localhost:8080/local/dev-token \
  -H 'Content-Type: application/json' \
  -d '{"clientId": "CLIENT001", "scope": "notifications:read notifications:replay"}'
```

Returns `{token, clientId, scope, expiresAt}`. Compose directly:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/local/dev-token -H 'Content-Type: application/json' \
  -d '{"clientId": "CLIENT001"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/notification_events
```

`clientId` is required. `scope` defaults to `"notifications:read notifications:replay"` if
omitted. Default token lifetime is 1 hour, which is also the hard ceiling — a longer
`lifetimeSeconds` in the request body is silently clamped to it, not rejected.

From Insomnia: the "Dev Token" folder's "Issue Dev Token (POST)" request does this and
auto-writes the result into the `jwt_token` environment variable — see the repo root `README.md`.

**Requires the app running** (unlike the old shell script, which only needed the key file). If you
need a token without the app up — e.g. scripting something before `bootRun` finishes — that path
no longer exists; start the app first.

## Negative-test tokens (wrong audience, expired, missing `client_id`)

Not available through `/local/dev-token`. These exist only in the automated test suite (see
`JwtClaimValidatorsTest` and similar), which builds malformed tokens directly rather than through
either tool. Nothing in `src/test` ever shelled out to a script for this, so removing it changed
no test.

## Regenerating the key pair

The local key does not rotate operationally. If you want a fresh one anyway:

```
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out dev-private.pem
openssl rsa -pubout -in dev-private.pem -out dev-public.pem
```

Keep the RSA key at least 2048 bits — the startup validator (TASK-008-22) fails boot below that.
