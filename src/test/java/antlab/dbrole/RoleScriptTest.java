package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** db/roles/antlab_owner_app.sql on an empty and on a migrated database (R1-R4, R6-R8, R10). Own container, see {@link RoleTestServer}. */
class RoleScriptTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    private static final String ATTRIBUTES = """
            SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolconnlimit
            FROM pg_roles WHERE rolname IN ('antlab_owner', 'antlab_app') ORDER BY rolname""";

    /** Everything the script may touch, as text: two snapshots are equal exactly when the script changed nothing. */
    static String snapshot(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT to_jsonb(r)::text FROM (" + ATTRIBUTES + ") r").toString(),
                jdbc.queryForList("""
                        SELECT jsonb_build_object('role', setrole::regrole::text, 'db', setdatabase, 'config', setconfig)::text
                        FROM pg_db_role_setting WHERE setrole IN (SELECT oid FROM pg_roles WHERE rolname LIKE 'antlab\\_%')
                        ORDER BY 1""", String.class).toString(),
                jdbc.queryForList("SELECT jsonb_build_object('owner', nspowner::regrole::text, 'acl', nspacl::text)::text "
                        + "FROM pg_namespace WHERE nspname = 'public'", String.class).toString(),
                jdbc.queryForList("SELECT coalesce(datacl::text, 'default') FROM pg_database WHERE datname = current_database()",
                        String.class).toString(),
                jdbc.queryForList("""
                        SELECT jsonb_build_object('rel', relname, 'owner', relowner::regrole::text, 'acl', relacl::text)::text
                        FROM pg_class WHERE relnamespace = 'public'::regnamespace ORDER BY relname""", String.class).toString(),
                jdbc.queryForList("SELECT roleid::regrole::text || '>' || member::regrole::text FROM pg_auth_members "
                        + "WHERE roleid IN (SELECT oid FROM pg_roles WHERE rolname LIKE 'antlab\\_%') "
                        + "OR member IN (SELECT oid FROM pg_roles WHERE rolname LIKE 'antlab\\_%') ORDER BY 1",
                        String.class).toString());
    }

    private static boolean publicHas(JdbcTemplate jdbc, String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class));
    }

    private static List<String> roleNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT rolname FROM pg_roles WHERE rolname LIKE 'antlab\\_%' ORDER BY 1", String.class);
    }

    @Test
    void r1_freshDatabaseGetsBothRolesWithTheDocumentedAttributes() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r1")) {
            SERVER.runRoleScript(db.name);

            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList(ATTRIBUTES)).containsExactly(
                    Map.of("rolname", "antlab_app", "rolcanlogin", true, "rolsuper", false, "rolcreatedb", false,
                            "rolcreaterole", false, "rolreplication", false, "rolbypassrls", false, "rolconnlimit", 20),
                    Map.of("rolname", "antlab_owner", "rolcanlogin", true, "rolsuper", false, "rolcreatedb", false,
                            "rolcreaterole", false, "rolreplication", false, "rolbypassrls", false, "rolconnlimit", 5));
            assertThat(jdbc.queryForList("""
                    SELECT setrole::regrole::text || ' ' || array_to_string(setconfig, ',') FROM pg_db_role_setting
                    WHERE setrole IN (SELECT oid FROM pg_roles WHERE rolname LIKE 'antlab\\_%') ORDER BY 1""", String.class))
                    .containsExactly("antlab_app search_path=public", "antlab_owner search_path=public");
            assertThat(jdbc.queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public'",
                    String.class)).isEqualTo("antlab_owner");
            assertThat(publicHas(jdbc, """
                    SELECT EXISTS (SELECT 1 FROM pg_namespace n, aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) x
                                   WHERE n.nspname = 'public' AND x.grantee = 0 AND x.privilege_type = 'CREATE')""")).isFalse();
            assertThat(publicHas(jdbc, """
                    SELECT EXISTS (SELECT 1 FROM pg_database d, aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) x
                                   WHERE d.datname = current_database() AND x.grantee = 0 AND x.privilege_type = 'TEMPORARY')"""))
                    .isFalse();
            assertThat(publicHas(jdbc, "SELECT has_database_privilege('antlab_app', current_database(), 'CONNECT') "
                    + "AND has_schema_privilege('antlab_app', 'public', 'USAGE')")).isTrue();
        }
    }

    @Test
    void r2_runningTwiceChangesNothing() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r2")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            String first = snapshot(db.superuser());

            SERVER.runRoleScript(db.name);

            assertThat(snapshot(db.superuser())).isEqualTo(first);
            assertThat(first).contains("antlab_owner").contains("member");
        }
    }

    @Test
    void r3_theTargetDatabaseMustBeNamedAndMatch() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r3")) {
            assertThatThrownBy(() -> SERVER.runScript(db.name, null, RoleTestServer.roleScript()))
                    .hasMessageContaining("name the target database first");
            assertThatThrownBy(() -> SERVER.runScript(db.name, "some_other_db", RoleTestServer.roleScript()))
                    .hasMessageContaining("antlab.expected_database is \"some_other_db\"").hasMessageContaining("nothing was changed");
            assertThatThrownBy(() -> SERVER.runScript(db.name, "", RoleTestServer.roleScript()))
                    .hasMessageContaining("name the target database first");
            assertThat(roleNames(db.superuser())).isEmpty();
        }
    }

    @Test
    void r3_thePracticeDatabaseIsRefused() throws SQLException {
        SERVER.execute(SERVER.container().getDatabaseName(), "CREATE DATABASE antlab TEMPLATE template0");
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r3p")) {
            assertThatThrownBy(() -> SERVER.runScript("antlab", "antlab", RoleTestServer.roleScript()))
                    .hasMessageContaining("refusing the practice database");
            assertThat(roleNames(db.superuser())).isEmpty();
        } finally {
            SERVER.execute(SERVER.container().getDatabaseName(), "DROP DATABASE IF EXISTS antlab WITH (FORCE)");
        }
    }

    @Test
    void r4_aCallerThatIsNotASuperuserIsRefused() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r4")) {
            SERVER.execute(db.name, "CREATE ROLE plain_caller LOGIN CREATEROLE PASSWORD '" + RoleTestServer.PASSWORD + "'");
            try (var c = SERVER.connect(db.name, "plain_caller", RoleTestServer.PASSWORD);
                    var s = c.createStatement()) {
                s.execute("SET antlab.expected_database = '" + db.name + "'");
                assertThatThrownBy(() -> s.execute(RoleTestServer.roleScript()))
                        .hasMessageContaining("run this script as a superuser");
            } finally {
                SERVER.execute(db.name, "DROP ROLE plain_caller");
            }
            assertThat(roleNames(db.superuser())).isEmpty();
        }
    }

    @Test
    void r6_aFunctionInPublicThatTheOwnerDoesNotOwnIsRefused() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r6f")) {
            SERVER.execute(db.name, "CREATE FUNCTION public.probe_fn() RETURNS int LANGUAGE sql AS 'SELECT 1'");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("public holds function").hasMessageContaining("probe_fn()").hasMessageContaining("nothing was changed");
            assertThat(roleNames(db.superuser())).isEmpty();
        }
    }

    @Test
    void r6_aTypeInPublicThatTheOwnerDoesNotOwnIsRefused() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r6t")) {
            SERVER.execute(db.name, "CREATE TYPE public.probe_kind AS ENUM ('a')");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("public holds type").hasMessageContaining("probe_kind");
            assertThat(roleNames(db.superuser())).isEmpty();
        }
    }

    @Test
    void r6_aRelationOwnedByTheAppRoleIsRefusedAndNothingMoves() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r6r")) {
            SERVER.execute(db.name, "CREATE ROLE antlab_app LOGIN");
            SERVER.execute(db.name, "CREATE TABLE public.probe_t (i int)");
            SERVER.execute(db.name, "ALTER TABLE public.probe_t OWNER TO antlab_app");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("antlab_app owns").hasMessageContaining("nothing was changed");

            JdbcTemplate jdbc = db.superuser();
            assertThat(roleNames(jdbc)).containsExactly("antlab_app");
            assertThat(jdbc.queryForObject("SELECT relowner::regrole::text FROM pg_class WHERE relname = 'probe_t'", String.class))
                    .isEqualTo("antlab_app");
            assertThat(jdbc.queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public'",
                    String.class)).isNotEqualTo("antlab_owner");
        }
    }

    @Test
    void r7_driftIsConvergedBack() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r7")) {
            SERVER.runRoleScript(db.name);
            String clean = snapshot(db.superuser());
            SERVER.execute(db.name, "ALTER ROLE antlab_app CREATEDB");
            SERVER.execute(db.name, "ALTER ROLE antlab_owner CREATEROLE CONNECTION LIMIT 99");
            SERVER.execute(db.name, "GRANT pg_write_all_data TO antlab_app");
            SERVER.execute(db.name, "GRANT pg_read_all_data TO antlab_owner");
            SERVER.execute(db.name, "ALTER ROLE antlab_app IN DATABASE " + db.name + " SET search_path = x");
            SERVER.execute(db.name, "ALTER ROLE antlab_app SET statement_timeout = '1ms'");
            SERVER.execute(db.name, "GRANT CREATE ON SCHEMA public TO antlab_app");
            SERVER.execute(db.name, "GRANT CREATE, TEMPORARY ON DATABASE " + db.name + " TO antlab_app");
            SERVER.execute(db.name, "GRANT CREATE ON SCHEMA public TO PUBLIC");
            assertThat(snapshot(db.superuser())).isNotEqualTo(clean);

            SERVER.runRoleScript(db.name);

            assertThat(snapshot(db.superuser())).isEqualTo(clean);
        }
    }

    @Test
    void r8_aPublicTableGrantIsRefusedAndTheWholeRunRollsBack() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.execute(db.name, "GRANT SELECT ON member TO PUBLIC");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("PUBLIC holds rights on public.member").hasMessageContaining("everything was rolled back");

            JdbcTemplate jdbc = db.superuser();
            assertThat(roleNames(jdbc)).as("a role created by the failed run is gone").isEmpty();
            assertThat(jdbc.queryForObject("SELECT relowner::regrole::text FROM pg_class WHERE relname = 'member'", String.class))
                    .as("ownership is unchanged").isNotEqualTo("antlab_owner");
        }
    }

    @Test
    void r8_aPublicSequenceGrantIsRefused() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8s")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.execute(db.name, "GRANT USAGE ON SEQUENCE member_id_seq TO PUBLIC");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("PUBLIC holds rights on public.member_id_seq");
        }
    }

    @Test
    void r8_aColumnGrantToTheAppRoleIsRefused() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8c")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            SERVER.execute(db.name, "GRANT UPDATE (name) ON member TO antlab_app");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("column-level rights are granted on public.member");
        }
    }

    @Test
    void r8_onlyTheRegimeColumnUpdateOfMonthBlockIsToleratedAsAColumnGrantToTheAppRole() throws SQLException {
        String regimeUpdate = "GRANT UPDATE (rate_dir, growth_dir, fx_dir, sentiment_dir, rate_strength, growth_strength, fx_strength,"
                + " sentiment_strength, prob_p1, prob_p2, prob_p3, prob_p4, regime_label, indicator_z) ON month_block TO antlab_app";
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8m")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            SERVER.execute(db.name, regimeUpdate);

            SERVER.runRoleScript(db.name);
        }
        for (String other : new String[] { "GRANT UPDATE (month_start) ON month_block TO antlab_app",
                "GRANT UPDATE (event_summary) ON month_block TO antlab_app", "GRANT UPDATE (events_reviewed) ON month_block TO antlab_app",
                "GRANT INSERT (regime_label) ON month_block TO antlab_app", "GRANT SELECT (regime_label) ON month_block TO antlab_app",
                "GRANT REFERENCES (regime_label) ON month_block TO antlab_app",
                "GRANT UPDATE (name) ON asset TO antlab_app", "GRANT UPDATE (rate_dir) ON month_block TO PUBLIC" }) {
            try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8n")) {
                SERVER.migrate(db.name, SERVER.container().getUsername(), false);
                SERVER.runRoleScript(db.name);
                SERVER.execute(db.name, regimeUpdate);
                SERVER.execute(db.name, other);

                assertThatThrownBy(() -> SERVER.runRoleScript(db.name)).as(other)
                        .hasMessageContaining("column-level rights are granted on public.");
            }
        }
    }

    @Test
    void r8_onlyTheCloseColumnUpdateOfLeagueIsToleratedAsAColumnGrantToTheAppRole() throws SQLException {
        String closeUpdate = "GRANT UPDATE (status, closed_at, version, updated_at) ON league TO antlab_app";
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8l")) {
            SERVER.migrate(db.name, SERVER.container().getUsername(), false);
            SERVER.runRoleScript(db.name);
            SERVER.execute(db.name, closeUpdate);

            SERVER.runRoleScript(db.name);
        }
        for (String other : new String[] { "GRANT UPDATE (seed) ON league TO antlab_app",
                "GRANT UPDATE (max_entries) ON league TO antlab_app", "GRANT INSERT (status) ON league TO antlab_app",
                "GRANT SELECT (seed) ON league TO antlab_app", "GRANT UPDATE (status) ON league TO PUBLIC",
                "GRANT UPDATE (member_id) ON league_entry TO antlab_app" }) {
            try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8m2")) {
                SERVER.migrate(db.name, SERVER.container().getUsername(), false);
                SERVER.runRoleScript(db.name);
                SERVER.execute(db.name, closeUpdate);
                SERVER.execute(db.name, other);

                assertThatThrownBy(() -> SERVER.runRoleScript(db.name)).as(other)
                        .hasMessageContaining("column-level rights are granted on public.");
            }
        }
    }

    @Test
    void r8_defaultPrivilegesForPublicOrTheAppRoleAreRefused() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "r8d")) {
            SERVER.runRoleScript(db.name);
            SERVER.execute(db.name, "ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public GRANT SELECT ON TABLES TO antlab_app");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                    .hasMessageContaining("default privileges of antlab_owner grant on object type \"r\"");
        }
    }

    @Test
    void anInstalledExtensionIsRefused() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "ext")) {
            SERVER.execute(db.name, "CREATE EXTENSION pgcrypto");

            assertThatThrownBy(() -> SERVER.runRoleScript(db.name)).hasMessageContaining("extension \"pgcrypto\" is installed");
        }
    }

    @Test
    void aMembershipOfAnotherRoleInTheOwnerIsRefused() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "mem")) {
            SERVER.runRoleScript(db.name);
            SERVER.execute(db.name, "CREATE ROLE sneaky LOGIN");
            SERVER.execute(db.name, "GRANT antlab_owner TO sneaky");
            try {
                assertThatThrownBy(() -> SERVER.runRoleScript(db.name))
                        .hasMessageContaining("role sneaky is a member of antlab_owner");
            } finally {
                SERVER.execute(db.name, "DROP ROLE sneaky");
            }
        }
    }

    @Test
    void r10_theScriptsCarryNoPasswordNoDatabaseNameAndNoPsqlCommand() throws IOException {
        String code = String.join("\n", RoleTestServer.roleScript().lines().filter(l -> !l.stripLeading().startsWith("--")).toList());

        assertThat(code).doesNotContainIgnoringCase("password");
        assertThat(code).doesNotContain("antlab_api");
        assertThat(code.lines().filter(l -> l.stripLeading().startsWith("\\"))).isEmpty();
        assertThat(code.lines().filter(l -> l.contains("current_database() = '")).toList())
                .as("the only database-name literal is the practice database").hasSize(1)
                .allMatch(l -> l.contains("'antlab'"));

        Path wrapper = Path.of("db/roles/initdb/10-antlab-roles.sh");
        String shell = Files.readString(wrapper);
        assertThat(shell).contains("-v ON_ERROR_STOP=1").contains("\"$POSTGRES_DB\"").contains("set -eu")
                .contains("/antlab-roles/antlab_owner_app.sql");
        assertThat(shell.toLowerCase()).doesNotContain("password");
        assertThat(Files.isExecutable(wrapper)).as("the wrapper is executable").isTrue();
    }
}
