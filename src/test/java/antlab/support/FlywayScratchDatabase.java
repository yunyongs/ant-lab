package antlab.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway database inside the test container, migrated by the production Flyway scripts up to a chosen version.
 * Created on construction and dropped on {@link #close()}; the container's own database is never touched.
 */
public final class FlywayScratchDatabase implements AutoCloseable {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PostgreSQLContainer container;
    private final String name;
    private final DataSource dataSource;

    public FlywayScratchDatabase(PostgreSQLContainer container, String prefix) {
        this.container = container;
        this.name = prefix + "_" + SEQ.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 100000);
        PsqlScriptRunner.requireSafeDatabase(name);
        admin("CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'");
        admin("ALTER DATABASE " + name + " SET timezone TO 'Asia/Seoul'");
        this.dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + container.getHost() + ":" + container.getFirstMappedPort() + "/" + name,
                container.getUsername(), container.getPassword());
    }

    public String name() {
        return name;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Applies the production migrations up to and including {@code target} ("latest" for all). */
    public MigrateResult migrateTo(String target) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public").defaultSchema("public")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .target(target)
                .load();
        return flyway.migrate();
    }

    @Override
    public void close() {
        admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    private void admin(String sql) {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + container.getHost() + ":" + container.getFirstMappedPort() + "/"
                        + container.getDatabaseName(), container.getUsername(), container.getPassword());
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("scratch database admin statement failed: " + sql, e);
        }
    }
}
