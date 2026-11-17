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

    private static final String TABLE_RIGHT =
            "the DB user holds DELETE, TRUNCATE, REFERENCES, TRIGGER or MAINTAIN on a table in schema public";

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
                    "the DB user is, or is a member of, the Flyway user (antlab_owner)", TABLE_RIGHT,
                    "the DB user has rights on flyway_schema_history");
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

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user owns relations in schema public",
                    TABLE_RIGHT);
        }
    }

    @Test
    void ownershipOfASequenceOrAViewAloneIsRefused() throws SQLException {
        try (var db = prepared("g7s")) {
            db.superuserExecute("CREATE SEQUENCE probe_seq");
            db.superuserExecute("ALTER SEQUENCE probe_seq OWNER TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user owns relations in schema public");
        }
        try (var db = prepared("g7v")) {
            db.superuserExecute("CREATE VIEW probe_view AS SELECT 1 AS i");
            db.superuserExecute("ALTER VIEW probe_view OWNER TO antlab_app");

            // The owner of a view holds DELETE on it, which the table-right check now also reports.
            assertThat(violations(db, "antlab_app")).containsExactly("the DB user owns relations in schema public",
                    TABLE_RIGHT);
        }
    }

    @Test
    void aHandGrantedDeleteOnATableIsRefusedEvenForAnotherAppRoleName() throws SQLException {
        try (var db = prepared("g13")) {
            db.superuserExecute("GRANT DELETE ON holding TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly(TABLE_RIGHT);
        }
        try (var db = prepared("g13b")) {
            db.superuserExecute("CREATE ROLE legacy_app LOGIN PASSWORD '" + RoleTestServer.PASSWORD + "'");
            db.superuserExecute("GRANT USAGE ON SCHEMA public TO legacy_app");
            db.superuserExecute("GRANT ALL ON ALL TABLES IN SCHEMA public TO legacy_app");

            assertThat(violations(db, "legacy_app")).containsExactly(TABLE_RIGHT,
                    "the DB user has rights on flyway_schema_history");
        } finally {
            SERVER.execute(SERVER.container().getDatabaseName(), "DROP ROLE IF EXISTS legacy_app");
        }
    }

    @Test
    void everyForbiddenTableRightIsRefusedOnItsOwn() throws SQLException {
        for (String right : List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN")) {
            try (var db = prepared("g14")) {
                db.superuserExecute("GRANT " + right + " ON asset TO antlab_app");

                assertThat(violations(db, "antlab_app")).as(right).containsExactly(TABLE_RIGHT);
            }
        }
    }

    @Test
    void aDeleteRightOnAViewIsRefused() throws SQLException {
        try (var db = prepared("g18")) {
            db.superuserExecute("CREATE VIEW v_ledger AS SELECT * FROM cash_ledger");
            db.superuserExecute("GRANT DELETE, SELECT ON v_ledger TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly(TABLE_RIGHT);
        }
    }

    @Test
    void membershipOfAWriteAllDataRoleIsRefused() throws SQLException {
        try (var db = prepared("g15")) {
            db.superuserExecute("GRANT pg_write_all_data TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly(TABLE_RIGHT,
                    "the DB user has rights on flyway_schema_history");
        }
    }

    @Test
    void anyRightOnTheMigrationHistoryIsRefused() throws SQLException {
        try (var db = prepared("g16")) {
            db.superuserExecute("GRANT SELECT ON flyway_schema_history TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user has rights on flyway_schema_history");
        }
        try (var db = prepared("g16b")) {
            db.superuserExecute("GRANT pg_read_all_data TO antlab_app");

            assertThat(violations(db, "antlab_app")).containsExactly("the DB user has rights on flyway_schema_history");
        }
    }

    @Test
    void aDatabaseWithoutTheMigrationHistoryDoesNotBreakTheCheck() throws SQLException {
        try (var db = prepared("g17")) {
            db.superuserExecute("DROP TABLE flyway_schema_history");

            assertThat(violations(db, "antlab_app")).isEmpty();
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
