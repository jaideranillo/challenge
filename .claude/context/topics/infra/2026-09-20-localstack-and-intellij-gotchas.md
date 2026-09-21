---
name: infra-localstack-intellij-gotchas
description: LocalStack licensing pin and IntelliJ Active-profiles field gotchas discovered wiring the local dev environment
metadata:
  type: infra
---

# LocalStack and IntelliJ local-dev gotchas

- **LocalStack images after `2026.3.0` require `LOCALSTACK_AUTH_TOKEN`** (license gate, container exits code 55 "License activation failed" without one). `compose.yaml`'s `localstack` service is pinned to `localstack/localstack:4.4.0`, the last pre-license release, so it starts with no token/account.
- **IntelliJ's Spring Boot run configuration "Active profiles" field takes the bare profile name only.** Pasting the full CLI flag (`--spring.profiles.active=local`) into it produces a literal invalid profile named `--spring.profiles.active=local` and the app fails at startup (`ProfilesValidator`, "Profile ... must start and end with a letter or digit"). Just type `local`.
- **Real `aws` CLI vs `awslocal`:** plain `aws sqs ...` against LocalStack fails with `ExpiredToken` unless given `--endpoint-url http://localhost:4566` and dummy creds (`AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test`) explicitly — it otherwise uses the real AWS profile/credentials. `awslocal` (via `docker exec <container> awslocal ...`, or `pip install awscli-local` for a local binary) wraps the endpoint/creds automatically.
- **OTLP metrics-push `ConnectException` noise is expected and harmless** whenever `grafana-lgtm` isn't running (e.g. host port 3000 already taken elsewhere) — the app functions fully without it, just logs a stacktrace every push interval.

See [[project-feat-001-local-dev-environment]] for the feature these were found wiring.
