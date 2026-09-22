package com.cobre.challenge.adapter.out.persistence.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.util.StringUtils;

/**
 * Wires the {@code challenge_api} connection pool (ADR-007 §5.4) alongside the existing
 * pipeline pool.
 *
 * <p>Declaring a second {@link DataSource} bean ({@link #apiDataSource}) makes Spring
 * Boot's own {@code DataSourceAutoConfiguration}/{@code JdbcTemplateAutoConfiguration}/
 * {@code DataSourceTransactionManagerAutoConfiguration} back off entirely - each is
 * gated by {@code @ConditionalOnMissingBean}/{@code @ConditionalOnSingleCandidate} on the
 * exact types this class also produces. So this class also redeclares the primary
 * {@code dataSource}, {@code namedParameterJdbcTemplate} and {@code transactionManager}
 * beans, marked {@code @Primary}, reproducing exactly what Boot would have auto-configured
 * (same Docker Compose / Testcontainers {@link JdbcConnectionDetails} discovery), so every
 * existing pipeline repository that autowires those types by name/type keeps working
 * unchanged. Only the credentials differ: the primary pool now connects as
 * {@code challenge_pipeline} (V5), sourced from {@code spring.datasource.username/password}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiDataSourceProperties.class)
public class ApiDataSourceConfig {

    // The API pool serves three read endpoints only; virtual threads don't remove the
    // DB-side connection ceiling, so this pool stays deliberately small.
    private static final int API_POOL_MAX_SIZE = 5;

    /**
     * The pipeline pool. Connects as {@code challenge_pipeline} (ADR-007 §5.4) via
     * {@code spring.datasource.username/password}; URL/driver still come from Docker
     * Compose / Testcontainers when present, exactly as before this task.
     */
    @Primary
    @Bean
    DataSource dataSource(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
        return buildDataSource(properties, connectionDetails.getIfAvailable(), properties.determineUsername(),
                properties.determinePassword());
    }

    @Primary
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Primary
    @Bean
    DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    /**
     * The {@code challenge_api} pool (ADR-007 §5.4). Never {@code @Primary}. Its URL is
     * derived from the primary pool's own resolved connection details, not configured
     * separately - two independently-configured URLs is a way to point this pool at a
     * different database and never notice.
     */
    @Bean
    DataSource apiDataSource(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetails,
            ApiDataSourceProperties apiProperties) {
        HikariDataSource dataSource = (HikariDataSource) buildDataSource(properties,
                connectionDetails.getIfAvailable(), apiProperties.username(), apiProperties.password());
        dataSource.setPoolName("challenge-api-pool");
        dataSource.setMaximumPoolSize(API_POOL_MAX_SIZE);
        assertRoleCannotBypassPolicies(dataSource);
        return dataSource;
    }

    @Bean
    NamedParameterJdbcTemplate apiJdbcTemplate(@Qualifier("apiDataSource") DataSource apiDataSource) {
        return new NamedParameterJdbcTemplate(apiDataSource);
    }

    @Bean
    DataSourceTransactionManager apiTransactionManager(@Qualifier("apiDataSource") DataSource apiDataSource) {
        return new DataSourceTransactionManager(apiDataSource);
    }

    private DataSource buildDataSource(DataSourceProperties properties, JdbcConnectionDetails connectionDetails,
            String username, String password) {
        HikariDataSource dataSource = new HikariDataSource();
        String url = connectionDetails != null ? connectionDetails.getJdbcUrl() : properties.determineUrl();
        String driverClassName = connectionDetails != null ? connectionDetails.getDriverClassName()
                : properties.determineDriverClassName();
        dataSource.setJdbcUrl(url);
        if (StringUtils.hasText(driverClassName)) {
            dataSource.setDriverClassName(driverClassName);
        }
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * ADR-007 Consequences: fail startup, not the first request, if the API pool's role
     * could silently disable row level security - by bypassing it, by being a superuser,
     * or by owning (or being a member of the owner of) a tenant table.
     */
    private void assertRoleCannotBypassPolicies(DataSource apiDataSource) {
        String sql = """
                SELECT r.rolbypassrls, r.rolsuper,
                       pg_has_role(r.rolname, 'challenge_owner', 'member') AS owner_member,
                       EXISTS (
                           SELECT 1 FROM pg_tables t
                           WHERE t.tableowner = r.rolname
                             AND t.tablename IN ('notification_events', 'subscriptions', 'deliveries', 'delivery_attempts')
                       ) AS owns_table
                FROM pg_roles r
                WHERE r.rolname = current_user
                """;
        try (Connection connection = apiDataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Could not resolve current_user in pg_roles for the API pool");
            }
            boolean bypassRls = resultSet.getBoolean("rolbypassrls");
            boolean superuser = resultSet.getBoolean("rolsuper");
            boolean ownerMember = resultSet.getBoolean("owner_member");
            boolean ownsTable = resultSet.getBoolean("owns_table");
            if (bypassRls || superuser || ownerMember || ownsTable) {
                throw new IllegalStateException(
                        ("The API pool's role can bypass row level security (bypassRls=%s, superuser=%s, "
                                + "ownerMember=%s, ownsTable=%s) - RLS would be silently disabled (ADR-007 §5.4)")
                                .formatted(bypassRls, superuser, ownerMember, ownsTable));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to verify the API pool's role cannot bypass RLS", e);
        }
    }
}
