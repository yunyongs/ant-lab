package antlab.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL 17 container of a test JVM. Every test class and Spring context that needs a database shares it and
 * isolates itself with its own database ({@link #createDatabase(String)}); the container is never stopped by a test, the
 * Testcontainers resource reaper removes it when the JVM ends. Nothing here is {@code testcontainers.reuse}: it never
 * outlives the JVM.
 *
 * <p>Only tests that change cluster-wide state (roles, privileges) keep a container of their own, see
 * {@link antlab.dbrole.RoleTestServer}.
 */
public final class SharedPostgres {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static PostgreSQLContainer container;

    private SharedPostgres() {
    }

    /**
     * Spring Boot's Testcontainers support stops every container bean when its context closes, whatever the bean's destroy
     * method says, and a context can close while others still use the container (cache eviction). So stop is a no-op here;
     * the resource reaper removes the container when the JVM ends.
     */
    private static final class Shared extends PostgreSQLContainer {
        Shared(String image) {
            super(image);
        }

        @Override
        public void stop() {
        }
    }

    /** Starts the container on first use; later calls return the same running instance. */
    public static synchronized PostgreSQLContainer container() {
        if (container != null && !container.isRunning()) {
            // a dead singleton would fail every later test with unrelated connection errors; say what happened instead
            throw new IllegalStateException("shared test container was removed or stopped from outside the test JVM");
        }
        if (container == null) {
            PostgreSQLContainer created = new Shared(PostgresTestcontainersConfig.IMAGE)
                    // lets a container guard recognise the one long-lived container of this JVM (it lives as long as the test run)
                    .withLabel("antlab.shared-test-db", "true")
                    // same server time zone as the compose db
                    .withEnv("TZ", "Asia/Seoul")
                    .withEnv("PGTZ", "Asia/Seoul")
                    // Budget: cached contexts (cap 32) x Hikari pool (max 10, minimum-idle 0 and idle-timeout 10s in application-test.yml)
                    // stay far below 300 only because idle connections are released; keep those two settings when changing this.
                    // many Spring contexts stay cached at once, each with its own pool; small buffers keep the container light
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300", "-c", "shared_buffers=32MB");
            created.start();
            container = created;
        }
        return container;
    }

    /** A new empty database (UTF8, Asia/Seoul like the compose one) in the shared container; returns its name. */
    public static String createDatabase(String prefix) {
        PostgreSQLContainer postgres = container();
        String name = prefix + "_" + SEQ.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 100000);
        PsqlScriptRunner.requireSafeDatabase(name);
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'");
            s.execute("ALTER DATABASE " + name + " SET timezone TO 'Asia/Seoul'");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create database " + name, e);
        }
        return name;
    }

    /** JDBC URL of one database in the shared container. */
    public static String jdbcUrl(String database) {
        PostgreSQLContainer postgres = container();
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }
}
