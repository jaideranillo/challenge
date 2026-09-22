package com.cobre.challenge.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit text guard over the {@code V5} migration - no database. It catches an
 * accidental edit to the role grants in this phase; {@code ApiRoleGrantsTest} (deferred,
 * Testcontainers) is the authoritative version against the real catalog.
 */
class V5RoleMigrationTextTest {

    private static String sql;
    private static List<String> statements;

    @BeforeAll
    static void loadMigration() {
        sql = readMigrationTextWithoutComments();
        statements = Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Test
    void challengeApiCreateRoleStatementIsNoBypassRlsAndNothingElevated() {
        String createApiRole = statementContaining("CREATE ROLE challenge_api");

        assertThat(createApiRole.toUpperCase()).contains("NOBYPASSRLS");
        assertThat(withoutNegated(createApiRole, "BYPASSRLS")).doesNotContainIgnoringCase("BYPASSRLS");
        assertThat(withoutNegated(createApiRole, "SUPERUSER")).doesNotContainIgnoringCase("SUPERUSER");
        assertThat(withoutNegated(createApiRole, "CREATEROLE")).doesNotContainIgnoringCase("CREATEROLE");
    }

    @Test
    void challengeApiNeverBecomesAnOwnerOrAnOwnerMember() {
        assertThat(sql).doesNotContainPattern("(?i)OWNER\\s+TO\\s+challenge_api");
        assertThat(sql).doesNotContainPattern("(?i)GRANT\\s+challenge_owner\\s+TO\\s+challenge_api");
    }

    @Test
    void everyGrantToChallengeApiIsSelectOrUsageOnly() {
        List<String> grantsToApi = statements.stream()
                .filter(s -> s.toUpperCase().startsWith("GRANT"))
                .filter(s -> s.matches("(?is).*\\bON\\b.*\\bTO\\b.*\\bchallenge_api\\b.*"))
                .collect(Collectors.toList());

        assertThat(grantsToApi).isNotEmpty();

        for (String grant : grantsToApi) {
            String privileges = grant.replaceFirst("(?is)^GRANT\\s+", "").replaceFirst("(?is)\\s+ON\\s+.*$", "");
            List<String> tokens = Arrays.stream(privileges.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());

            assertThat(tokens).allMatch(t -> t.equals("SELECT") || t.equals("USAGE"));
            assertThat(tokens).noneMatch(t -> t.equals("INSERT") || t.equals("UPDATE") || t.equals("DELETE")
                    || t.equals("TRUNCATE") || t.equals("REFERENCES") || t.equals("ALL"));
        }
    }

    @Test
    void challengePipelineHasAnExplicitBypassRlsExemptionAndNoFallbackPolicyInThisFile() {
        String createPipelineRole = statementContaining("CREATE ROLE challenge_pipeline");

        assertThat(withoutNegated(createPipelineRole, "BYPASSRLS")).containsIgnoringCase("BYPASSRLS");
        assertThat(sql).doesNotContainPattern("(?i)TO\\s+challenge_pipeline\\s+USING\\s*\\(\\s*true\\s*\\)");
    }

    @Test
    void challengePipelineNeverBecomesAnOwnerMember() {
        assertThat(sql).doesNotContainPattern("(?i)GRANT\\s+challenge_owner\\s+TO\\s+challenge_pipeline");
    }

    private static String statementContaining(String marker) {
        return statements.stream()
                .filter(s -> s.toUpperCase().contains(marker.toUpperCase()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No statement found containing: " + marker));
    }

    private static String withoutNegated(String text, String keyword) {
        return text.replaceAll("(?i)NO" + keyword, "");
    }

    private static String readMigrationTextWithoutComments() {
        try (InputStream in = V5RoleMigrationTextTest.class
                .getResourceAsStream("/db/migration/V5__database_roles.sql")) {
            if (in == null) {
                throw new AssertionError("db/migration/V5__database_roles.sql not found on the test classpath");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return raw.replaceAll("(?m)--.*$", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
