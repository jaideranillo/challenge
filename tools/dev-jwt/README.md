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

```
./issue-token.sh --client-id CLIENT001 --scope "notifications:read"
```

Prints a single RS256 JWT on stdout and nothing else, so it composes directly:

```
curl -H "Authorization: Bearer $(./issue-token.sh --client-id CLIENT001 --scope "notifications:read")" \
  http://localhost:8080/notification_events
```

No IdP is involved anywhere in this flow.

`--client-id` is required; the script refuses to emit a token without it. `--scope` accepts
`notifications:read`, `notifications:replay`, or both space-delimited in one string. Default
token lifetime is 1 hour, which is also the hard ceiling (`--lifetime` cannot exceed it).

## Negative-test flags

For the 401 test paths in TASK-008-20 / TASK-008-28:

| Flag | Effect |
|---|---|
| `--wrong-audience` | signs the token with an audience that will not match the configured one |
| `--expired` | signs a token whose `exp` is already in the past |
| `--omit-client-id` | omits the `client_id` claim entirely |

Each flag changes only the one thing it names; none weaken the default (valid) output.

## Regenerating the key pair

The local key does not rotate operationally. If you want a fresh one anyway:

```
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out dev-private.pem
openssl rsa -pubout -in dev-private.pem -out dev-public.pem
```

Keep the RSA key at least 2048 bits — the startup validator (TASK-008-22) fails boot below that.
