package antlab.dbrole;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/** A database on the app role server for one Spring context: roles from the production script, app and Flyway users as in prod. */
final class AppRoleDatabase {

    private final RoleTestServer server = RoleTestServer.app();
    private final String name;

    private AppRoleDatabase(String prefix) {
        this.name = server.createDatabase(prefix);
        try {
            server.runRoleScript(name);
        } catch (SQLException e) {
            throw new IllegalStateException("role script failed: " + e.getMessage(), e);
        }
        server.setPasswords(name);
    }

    /** Registers the prod-like connection settings; the least-privilege guard is switched on like in prod. */
    static AppRoleDatabase register(DynamicPropertyRegistry registry, String prefix) {
        AppRoleDatabase db = new AppRoleDatabase(prefix);
        registry.add("spring.datasource.url", () -> db.server.url(db.name));
        registry.add("spring.datasource.username", () -> "antlab_app");
        registry.add("spring.datasource.password", () -> RoleTestServer.PASSWORD);
        registry.add("spring.flyway.user", () -> "antlab_owner");
        registry.add("spring.flyway.password", () -> RoleTestServer.PASSWORD);
        registry.add("antlab.db.least-privilege-check", () -> "true");
        return db;
    }

    String name() {
        return name;
    }

    RoleTestServer server() {
        return server;
    }

    /** The superuser connection: fixtures the app role may not write (history, the runner owner member) and catalog reads. */
    DataSource superuser() {
        return new DriverManagerDataSource(server.url(name), server.container().getUsername(), server.container().getPassword());
    }
}
