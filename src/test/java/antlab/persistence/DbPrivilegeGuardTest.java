package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.dbrole.RoleTestServer;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.jdbc.core.JdbcTemplate;

/** The prod startup guard on a real role server (G1-G5); the roles come from db/roles/antlab_owner_app.sql. */
class DbPrivilegeGuardTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    private static List<String> violations(RoleTestServer.Db db, String user) {
        return DbPrivilegeGuard.violations(db.as(user), "antlab_owner");
    }

    private static RoleTestServer.Db prepared(String prefix) throws SQLException {
        RoleTestServer.Db db = new RoleTestServer.Db(SERVER, prefix);
        db.createRoles();
        return db;
    }

    @Test
    void g1_theAppRoleAfterScriptAndCallbackPasses() throws SQLException {
        try (var db = prepared("g1")) {
            assertThat(violations(db, "antlab_app")).isEmpty();
        }
    }

    @Test
    void g2_aSuperuserIsRefused() throws SQLException {
        try (var db = prepared("g2")) {
            JdbcTemplate superuser = db.superuser();

            assertThat(DbPrivilegeGuard.violations(superuser, "antlab_owner")).contains("the DB user is a superuser");
        }
    }

    @Test
    void g3_theOwnerAsAppUserIsRefused() throws SQLException {
        try (var db = prepared("g3")) {
            assertThat(violations(db, "antlab_owner")).contains("the DB user owns relations in schema public",
                    "the DB user owns schema public", "the DB user can create objects in schema public",
                    "the DB user is, or is a member of, the Flyway user (antlab_owner)");
        }
    }

    @Test
    void g4_createOnSchemaPublicIsRefused() throws SQLException {
        try (var db = prepared("g4")) {
            db.superuserExecute("GRANT CREATE ON SCHEMA public TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user can create objects in schema public");
        }
    }

    @Test
    void g5_aMemberOfTheFlywayUserIsRefused() throws SQLException {
        try (var db = prepared("g5")) {
            db.superuserExecute("GRANT antlab_owner TO antlab_app");

            assertThat(violations(db, "antlab_app")).contains(
                    "the DB user is, or is a member of, the Flyway user (antlab_owner)");
        }
    }

    @Test
    void createDbCreateRoleBypassRlsAndTemporaryAreRefused() throws SQLException {
        try (var db = prepared("g6")) {
            db.superuserExecute("ALTER ROLE antlab_app CREATEDB CREATEROLE BYPASSRLS REPLICATION");
            db.superuserExecute("GRANT CREATE, TEMPORARY ON DATABASE " + db.name() + " TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user can create databases",
                    "the DB user can create roles", "the DB user bypasses row-level security", "the DB user can replicate",
                    "the DB user can create schemas in this database", "the DB user can create temporary tables");
        }
    }

    @Test
    void ownershipOfARelationIsRefused() throws SQLException {
        try (var db = prepared("g7")) {
            db.superuserExecute("ALTER TABLE member OWNER TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user owns relations in schema public");
        }
    }

    @Test
    void aBlankFlywayUserIsRefusedBecauseMigrationsWouldRunAsTheAppUser() throws SQLException {
        try (var db = prepared("g8")) {
            assertThat(DbPrivilegeGuard.violations(db.as("antlab_app"), "")).containsExactly(
                    "spring.flyway.user is not set, so migrations would run as the DB user");
            assertThat(DbPrivilegeGuard.violations(db.as("antlab_app"), null)).hasSize(1);
        }
    }

    @Test
    void aFlywayUserThatDoesNotExistIsNotAMembership() throws SQLException {
        try (var db = prepared("g9")) {
            assertThat(DbPrivilegeGuard.violations(db.as("antlab_app"), "no_such_role")).isEmpty();
        }
    }

    @Test
    void messagesCarryNoCredentialOrUrl() throws SQLException {
        try (var db = prepared("g10")) {
            db.superuserExecute("ALTER ROLE antlab_app CREATEDB");

            assertThat(String.join(";", violations(db, "antlab_app"))).doesNotContain("jdbc:").doesNotContain("password");
        }
    }

    private static ApplicationContextRunner runner(JdbcTemplate jdbc) {
        return new ApplicationContextRunner().withUserConfiguration(DbPrivilegeGuardConfig.class)
                .withBean(JdbcTemplate.class, () -> jdbc);
    }

    @Test
    void g6_withTheCheckOffOrUnsetNoGuardIsWired() throws SQLException {
        try (var db = prepared("g11")) {
            runner(db.superuser()).withPropertyValues("antlab.db.least-privilege-check=false")
                    .run(context -> assertThat(context).doesNotHaveBean("dbPrivilegeStartupCheck"));
            runner(db.superuser()).run(context -> assertThat(context).doesNotHaveBean("dbPrivilegeStartupCheck"));
        }
    }

    @Test
    void theWiredGuardStopsTheStartupOfASuperuserAndLetsTheAppRoleThrough() throws SQLException {
        try (var db = prepared("g12")) {
            runner(db.superuser()).withPropertyValues("antlab.db.least-privilege-check=true", "spring.flyway.user=antlab_owner")
                    .run(context -> assertThatThrownBy(() -> context.publishEvent(started(context)))
                            .hasMessageContaining("antlab.db.least-privilege-check failed").hasMessageContaining("superuser"));
            runner(db.as("antlab_app")).withPropertyValues("antlab.db.least-privilege-check=true", "spring.flyway.user=antlab_owner")
                    .run(context -> context.publishEvent(started(context)));
            runner(db.as("antlab_app")).withPropertyValues("antlab.db.least-privilege-check=true")
                    .run(context -> assertThatThrownBy(() -> context.publishEvent(started(context)))
                            .hasMessageContaining("spring.flyway.user is not set"));
        }
    }

    private static ApplicationStartedEvent started(org.springframework.context.ApplicationContext context) {
        return new ApplicationStartedEvent(new SpringApplication(), new String[0],
                (org.springframework.context.ConfigurableApplicationContext) context, Duration.ZERO);
    }
}
