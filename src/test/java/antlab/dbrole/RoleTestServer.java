package antlab.dbrole;

import antlab.support.PostgresTestcontainersConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL 17 container of its own for the role tests. Roles are cluster-wide and the tests change schema ownership,
 * so they never share the container of the other integration tests. Two instances live for the whole test JVM:
 * {@link #scripts()} for tests that run the role scripts (each test uses {@link Db}, which removes its database and the roles
 * on close) and {@link #app()} for Spring contexts that keep the roles for good.
 */
public final class RoleTestServer {

    /** Random per JVM and never printed: Testcontainers' pg_hba asks for a password on TCP. */
    public static final String PASSWORD = UUID.randomUUID().toString();

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static RoleTestServer scripts;
    private static RoleTestServer app;

    private final PostgreSQLContainer container;

    private RoleTestServer() {
        // Own container on purpose: roles and ownership are cluster-wide, so sharing would leak them into other tests.
        container = new PostgreSQLContainer(PostgresTestcontainersConfig.IMAGE).withEnv("TZ", "Asia/Seoul").withEnv("PGTZ", "Asia/Seoul");
        container.start();
    }

    public static synchronized RoleTestServer scripts() {
        if (scripts == null) {
            scripts = new RoleTestServer();
        }
        return scripts;
    }

    public static synchronized RoleTestServer app() {
        if (app == null) {
            app = new RoleTestServer();
        }
        return app;
    }

    public static String roleScript() {
        return read("db/roles/antlab_owner_app.sql");
    }

    public static String engineScript() {
        return read("db/roles/antlab_engine.sql");
    }

    public static String grafanaScript() {
        return read("db/roles/grafana_reader.sql");
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    public String url(String database) {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getFirstMappedPort() + "/" + database;
    }

    public PostgreSQLContainer container() {
        return container;
    }

    public Connection connect(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(url(database), user, password);
    }

    public Connection superuser(String database) throws SQLException {
        return connect(database, container.getUsername(), container.getPassword());
    }

    public JdbcTemplate jdbc(String database, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), user, password));
    }

    public JdbcTemplate superuserJdbc(String database) {
        return jdbc(database, container.getUsername(), container.getPassword());
    }

    /** A new empty database (UTF8, Asia/Seoul like the compose one). */
    public String createDatabase(String prefix) {
        String name = prefix + "_" + SEQ.incrementAndGet();
        try (Connection c = superuser(container.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'");
            s.execute("ALTER DATABASE " + name + " SET timezone TO 'Asia/Seoul'");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create database " + name, e);
        }
        return name;
    }

    /** Runs {@code sql} as the superuser in {@code database}; the statement may be a whole script. */
    public void execute(String database, String sql) {
        try (Connection c = superuser(database); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Runs a script that expects one named database, in one session as the superuser. */
    public void runScript(String database, String expectedDatabase, String script) throws SQLException {
        try (Connection c = superuser(database); Statement s = c.createStatement()) {
            if (expectedDatabase != null) {
                s.execute("SET antlab.expected_database = '" + expectedDatabase + "'");
            }
            s.execute(script);
        }
    }

    public void runRoleScript(String database) throws SQLException {
        runScript(database, database, roleScript());
    }

    public void runEngineScript(String database) throws SQLException {
        runScript(database, database, engineScript());
    }

    public void runGrafanaScript(String database) throws SQLException {
        runScript(database, database, grafanaScript());
    }

    /** Sets throwaway passwords on both roles (the script sets none). */
    public void setPasswords(String database) {
        execute(database, "ALTER ROLE antlab_owner PASSWORD '" + PASSWORD + "'");
        execute(database, "ALTER ROLE antlab_app PASSWORD '" + PASSWORD + "'");
    }

    /** Flyway as the given user. Callbacks are loaded from the production location when {@code withCallbacks}. */
    public MigrateResult migrate(String database, String user, boolean withCallbacks) {
        return flyway(database, user, withCallbacks).migrate();
    }

    public Flyway flyway(String database, String user, boolean withCallbacks) {
        String password = user.equals(container.getUsername()) ? container.getPassword() : PASSWORD;
        return Flyway.configure()
                .dataSource(url(database), user, password)
                .locations(withCallbacks
                        ? new String[] {"classpath:db/migration", "classpath:db/callback"}
                        : new String[] {"classpath:db/migration"})
                .schemas("public").defaultSchema("public")
                .baselineOnMigrate(false).cleanDisabled(true)
                .load();
    }

    /** One database for one test; closing drops it and then the cluster-wide roles, so the next test starts clean. */
    public static final class Db implements AutoCloseable {
        final RoleTestServer server;
        final String name;

        public Db(RoleTestServer server, String prefix) {
            this.server = server;
            this.name = server.createDatabase(prefix);
        }

        public String name() {
            return name;
        }

        public JdbcTemplate superuser() {
            return server.superuserJdbc(name);
        }

        public JdbcTemplate as(String role) {
            return server.jdbc(name, role, PASSWORD);
        }

        public void superuserExecute(String sql) {
            server.execute(name, sql);
        }

        /** The production order: role script, passwords, Flyway as the owner with the callback. */
        public void createRoles() throws SQLException {
            server.runRoleScript(name);
            server.setPasswords(name);
            server.migrate(name, "antlab_owner", true);
        }

        @Override
        public void close() {
            List<String> sql = new ArrayList<>();
            sql.add("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            sql.add("DROP ROLE IF EXISTS antlab_owner");
            sql.add("DROP ROLE IF EXISTS antlab_app");
            sql.add("DROP ROLE IF EXISTS antlab_engine");
            sql.add("DROP ROLE IF EXISTS grafana_reader");
            for (String statement : sql) {
                try (Connection c = server.superuser(server.container.getDatabaseName()); Statement s = c.createStatement()) {
                    s.execute(statement);
                } catch (SQLException e) {
                    throw new IllegalStateException("cleanup failed: " + statement + ": " + e.getMessage(), e);
                }
            }
        }
    }

    /** A SQL result as plain strings, for catalog snapshots. */
    static List<String> column(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
