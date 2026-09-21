# Testcontainers Postgres and the `compose.yaml` local dev Postgres are two different databases

**Fact:** `./gradlew test` (Testcontainers, `TestcontainersConfiguration`) spins an ephemeral Postgres per test run and never touches the persistent `compose.yaml` Postgres (`challenge-postgres-1`) that `bootRun` uses for local dev.

**Implication:** passing tests that assert schema/migration state (e.g. Flyway-applied tables, indexes) says nothing about whether the local dev database has actually been migrated. To apply migrations to the local dev Postgres, `./gradlew bootRun` must actually be run at least once — Flyway runs on app startup against whatever `spring.datasource` resolves to at runtime (the compose-managed instance when not on a test profile).

**Verification pattern:** `docker exec challenge-postgres-1 psql -U myuser -d mydatabase -c "\dt"` to check what's actually applied locally, independent of test results.
