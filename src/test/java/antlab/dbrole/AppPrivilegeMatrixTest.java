package antlab.dbrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The privilege classes of antlab_app (afterMigrate.sql), pinned by an independent copy: the test lists every table
 * itself, so adding a table without a class (or removing one) fails here first (P1-P7).
 */
class AppPrivilegeMatrixTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    static final Set<String> READ = Set.of("asset_group", "asset", "asset_position", "month_block", "asset_month_return",
            "fx_month", "month_event", "asset_month_attention");
    static final Set<String> APPEND = Set.of("season_path", "turn", "turn_price", "formation_change", "turn_feed",
            "reconciliation_log", "orders", "execution", "cash_ledger", "asset_daily_price", "season_agent_config", "agent_call");
    static final Set<String> MUTABLE = Set.of("member", "season", "cash_account", "holding", "lineup", "refresh_token",
            "agent_profile", "job_run");
    static final List<String> PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER",
            "MAINTAIN");

    private static Set<String> expected(String table) {
        if (READ.contains(table)) {
            return Set.of("SELECT");
        }
        if (APPEND.contains(table)) {
            return Set.of("SELECT", "INSERT");
        }
        if (MUTABLE.contains(table)) {
            return Set.of("SELECT", "INSERT", "UPDATE");
        }
        return Set.of();
    }

    private static List<String> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace "
                + "AND relkind IN ('r', 'p') ORDER BY relname", String.class);
    }

    /** Roles, passwords, Flyway as the owner with the callback; returns the superuser handle of the database. */
    private static JdbcTemplate prepare(RoleTestServer.Db db) throws SQLException {
        SERVER.runRoleScript(db.name);
        SERVER.setPasswords(db.name);
        SERVER.migrate(db.name, "antlab_owner", true);
        return db.superuser();
    }

    private static String sqlState(JdbcTemplate jdbc, String sql) {
        try (Connection c = jdbc.getDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            return "no error";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static String aclSnapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT relname || ' ' || coalesce(relacl::text, '-') FROM pg_class "
                + "WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r', 'p', 'S') ORDER BY relname",
                String.class).toString();
    }

    @Test
    void p2_theTestListsAndTheCatalogAgreeBothWays() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p2")) {
            JdbcTemplate jdbc = prepare(db);

            Set<String> listed = new TreeSet<>();
            listed.addAll(READ);
            listed.addAll(APPEND);
            listed.addAll(MUTABLE);
            assertThat(listed).as("a table must be in exactly one class").hasSize(READ.size() + APPEND.size() + MUTABLE.size());
            List<String> inDatabase = new ArrayList<>(tables(jdbc));
            inDatabase.remove("flyway_schema_history");

            assertThat(inDatabase).as("every table in public has a class (unclassified)").isSubsetOf(listed);
            assertThat(listed).as("every listed table exists (stale)").isSubsetOf(inDatabase);
        }
    }

    @Test
    void p1_everyTableHasExactlyItsClassRightsAndNoColumnLevelGrant() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p1")) {
            JdbcTemplate jdbc = prepare(db);

            List<String> wrong = new ArrayList<>();
            for (String table : tables(jdbc)) {
                for (String privilege : PRIVILEGES) {
                    boolean has = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT has_table_privilege('antlab_app', ?::regclass, ?)", Boolean.class, "public." + table, privilege));
                    if (has != expected(table).contains(privilege)) {
                        wrong.add(privilege + " on " + table + " is " + has);
                    }
                }
                boolean anyColumn = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT has_any_column_privilege('antlab_app', ?::regclass, 'UPDATE')", Boolean.class, "public." + table));
                assertThat(anyColumn).as("column-level UPDATE on " + table).isEqualTo(expected(table).contains("UPDATE"));
            }
            assertThat(wrong).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_class c, aclexplode(c.relacl) x
                    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'S') AND x.grantee = 0""",
                    Integer.class)).as("nothing is granted to PUBLIC").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c, aclexplode(c.relacl) x "
                    + "WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S' AND x.grantee <> c.relowner",
                    Integer.class)).as("no sequence grant").isZero();
        }
    }

    @Test
    void p3_theAppRoleCannotDoDdlDeleteOrWriteAppendOnlyTables() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p3")) {
            prepare(db);
            JdbcTemplate app = db.as("antlab_app");

            List<String> statements = List.of(
                    "CREATE TABLE x (i int)",
                    "CREATE TEMP TABLE x (i int)",
                    "CREATE SCHEMA s",
                    "ALTER TABLE asset ADD COLUMN z int",
                    "DROP TABLE turn",
                    "TRUNCATE cash_ledger",
                    "TRUNCATE member",
                    "DELETE FROM holding",
                    "DELETE FROM member",
                    "DELETE FROM cash_ledger",
                    "UPDATE cash_ledger SET amount = amount",
                    "UPDATE orders SET quantity = quantity",
                    "UPDATE execution SET quantity = quantity",
                    "UPDATE turn SET turn_no = turn_no",
                    "UPDATE asset_daily_price SET close_price = close_price",
                    "DELETE FROM asset_daily_price",
                    "SELECT * FROM orders FOR UPDATE",
                    "INSERT INTO asset DEFAULT VALUES",
                    "UPDATE asset SET name = name",
                    "UPDATE asset_month_return SET asset_id = asset_id",
                    "SELECT * FROM flyway_schema_history",
                    "INSERT INTO flyway_schema_history DEFAULT VALUES",
                    "SET ROLE antlab_owner",
                    "ALTER ROLE antlab_app SUPERUSER",
                    "CREATE ROLE sneaky",
                    "COMMENT ON TABLE asset IS 'x'");
            List<String> notDenied = new ArrayList<>();
            for (String statement : statements) {
                String state = sqlState(app, statement);
                if (!"42501".equals(state)) {
                    notDenied.add(statement + " -> " + state);
                }
            }
            assertThat(notDenied).as("statements that did not fail with 42501").isEmpty();
        }
    }

    @Test
    void p3_theAppRoleCanDoWhatTheCodeNeeds() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p3ok")) {
            prepare(db);
            JdbcTemplate app = db.as("antlab_app");

            assertThat(sqlState(app, "INSERT INTO member (email, name, password) VALUES ('p3@example.invalid', 'p3', '!')"))
                    .isEqualTo("no error");
            assertThat(sqlState(app, "SELECT * FROM member FOR UPDATE")).isEqualTo("no error");
            assertThat(sqlState(app, "UPDATE member SET name = 'p3b'")).isEqualTo("no error");
            assertThat(sqlState(app, "SELECT * FROM job_run FOR SHARE")).isEqualTo("no error");
            assertThat(sqlState(app, "SELECT count(*) FROM asset")).isEqualTo("no error");
            assertThat(sqlState(app, "SELECT current_user")).isEqualTo("no error");
        }
    }

    @Test
    void p4_withoutTheAppRoleTheCallbackDoesNothing() {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p4")) {
            String superuser = SERVER.container().getUsername();

            var first = SERVER.migrate(db.name, superuser, true);
            String acls = aclSnapshot(db.superuser());
            var second = SERVER.migrate(db.name, superuser, true);

            assertThat(first.success).isTrue();
            assertThat(second.success).isTrue();
            assertThat(acls).as("no ACL was written").doesNotContain("antlab_app").doesNotContain("{");
            assertThat(aclSnapshot(db.superuser())).isEqualTo(acls);
        }
    }

    @Test
    void p5_aTableWithoutAClassStopsTheMigrateAndNamesTheTable() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p5")) {
            prepare(db);
            try (Connection c = SERVER.connect(db.name, "antlab_owner", RoleTestServer.PASSWORD); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE probe_unclassified (i int)");
            }

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("probe_unclassified");
        }
    }

    @Test
    void p6_theCallbackRunsOnEveryMigrateAndChangesNothingWhenConverged() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p6")) {
            JdbcTemplate jdbc = prepare(db);
            String converged = aclSnapshot(jdbc);

            var result = SERVER.migrate(db.name, "antlab_owner", true);

            assertThat(result.migrationsExecuted).as("nothing is pending").isZero();
            assertThat(aclSnapshot(jdbc)).isEqualTo(converged);
        }
    }

    @Test
    void p7_planted_rights_are_taken_back_by_the_next_migrate_even_with_nothing_pending() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p7")) {
            JdbcTemplate jdbc = prepare(db);
            String converged = aclSnapshot(jdbc);
            try (Connection c = SERVER.connect(db.name, "antlab_owner", RoleTestServer.PASSWORD); Statement s = c.createStatement()) {
                s.execute("GRANT SELECT ON member TO PUBLIC");
                s.execute("GRANT DELETE ON holding TO antlab_app");
                s.execute("GRANT UPDATE ON cash_ledger TO antlab_app");
                s.execute("GRANT USAGE ON SEQUENCE member_id_seq TO antlab_app");
                s.execute("GRANT UPDATE (name) ON asset TO antlab_app");
            }
            assertThat(aclSnapshot(jdbc)).isNotEqualTo(converged);

            assertThat(SERVER.migrate(db.name, "antlab_owner", true).migrationsExecuted).isZero();

            assertThat(aclSnapshot(jdbc)).isEqualTo(converged);
            assertThat(jdbc.queryForObject("SELECT has_any_column_privilege('antlab_app', 'asset'::regclass, 'UPDATE')",
                    Boolean.class)).isFalse();
        }
    }
}
