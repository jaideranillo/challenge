# Flyway silently never runs without `spring-boot-starter-flyway` on Spring Boot 4.1.1

**Symptom:** `flyway-core` + `flyway-database-postgresql` on the classpath, `spring.flyway.*` configured, app boots cleanly — but no migration ever runs, no error, no log line about Flyway at all.

**Root cause:** Spring Boot 4 moved Flyway autoconfiguration out of `spring-boot-autoconfigure`/`spring-boot-sql` into its own BOM-managed starter, `spring-boot-starter-flyway`. Without that starter's autoconfiguration class on the classpath, the two Flyway artifacts sit there unused.

**Fix:** add `org.springframework.boot:spring-boot-starter-flyway` (unpinned, BOM-managed) alongside `flyway-core` and `flyway-database-postgresql`.

**How to verify:** boot log should show `FlywayExecutor`/`DbMigrate` lines. Absence of any Flyway log line at all (not even "no migrations found") is the tell that the starter is missing, not that migrations are absent.
