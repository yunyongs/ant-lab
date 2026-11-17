package antlab.support;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Throwaway PostgreSQL 17 for the tests and {@code bootTestRun}: the JVM-wide {@link SharedPostgres} container, with a new
 * empty database for every Spring context (so contexts never see each other's rows). It never connects to the owner's
 * always-on DB (127.0.0.1:5432).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    /** The image is decided in this one place. */
    public static final String IMAGE = "postgres:17";

    /** The shared container itself (tests that need host, port or the admin login inject it); it outlives every context. */
    @Bean(destroyMethod = "")
    PostgreSQLContainer postgres() {
        return SharedPostgres.container();
    }

    @Bean
    JdbcConnectionDetails postgresConnectionDetails(PostgreSQLContainer postgres) {
        String url = SharedPostgres.jdbcUrl(SharedPostgres.createDatabase("ctx"));
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return url;
            }

            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }
        };
    }
}
