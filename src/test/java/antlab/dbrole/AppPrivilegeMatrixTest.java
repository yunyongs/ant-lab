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
            "reconciliation_log", "orders", "execution", "cash_ledger", "asset_daily_price", "season_agent_config", "persona_version", "agent_call",
            "season_score", "rating_history", "projection", "series", "series_observation");
    static final Set<String> MUTABLE = Set.of("member", "season", "cash_account", "holding", "lineup", "refresh_token",
            "agent_profile", "job_run");
    static final List<String> PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER",
            "MAINTAIN");

    /** The one column-level right (M2-08): the regime label job fills these columns of month_block. An independent copy of the callback list. */
    static final String REGIME_TABLE = "month_block";
    static final Set<String> REGIME_COLUMNS = Set.of("rate_dir", "growth_dir", "fx_dir", "sentiment_dir", "rate_strength",
            "growth_strength", "fx_strength", "sentiment_strength", "prob_p1", "prob_p2", "prob_p3", "prob_p4", "regime_label",
            "indicator_z");

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

    /** What {@code has_any_column_privilege} reports: the table-level rights, plus UPDATE on a column of the regime table. */
    private static boolean expectedOnSomeColumn(String table, String privilege) {
        return expected(table).contains(privilege) || (table.equals(REGIME_TABLE) && privilege.equals("UPDATE"));
    }

    private static List<String> columnsOf(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT attname FROM pg_attribute WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped "
                + "ORDER BY attnum", String.class, "public." + table);
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
                String.class).toString() + jdbc.queryForList("SELECT c.relname || '.' || a.attname || ' ' || a.attacl::text "
                + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                + "WHERE c.relnamespace = 'public'::regnamespace AND a.attacl IS NOT NULL ORDER BY 1", String.class);
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
    void p1_everyTableHasExactlyItsClassRightsAndNoColumnLevelGrantBesidesTheRegimeColumns() throws SQLException {
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
                for (String columnPrivilege : List.of("SELECT", "INSERT", "UPDATE", "REFERENCES")) {
                    boolean anyColumn = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT has_any_column_privilege('antlab_app', ?::regclass, ?)", Boolean.class,
                            "public." + table, columnPrivilege));
                    assertThat(anyColumn).as("column-level " + columnPrivilege + " on " + table)
                            .isEqualTo(expectedOnSomeColumn(table, columnPrivilege));
                }
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
    void p10_exactlyTheRegimeColumnsCanBeUpdatedAndNoOtherColumnHasARightOfItsOwn() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p10")) {
            JdbcTemplate jdbc = prepare(db);

            List<String> columns = columnsOf(jdbc, REGIME_TABLE);
            assertThat(columns).as("the listed regime columns exist").containsAll(REGIME_COLUMNS);
            assertThat(columns).as("the table has columns that stay read-only").hasSizeGreaterThan(REGIME_COLUMNS.size());
            List<String> wrong = new ArrayList<>();
            for (String column : columns) {
                for (String privilege : List.of("UPDATE", "INSERT", "REFERENCES")) {
                    boolean has = Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege('antlab_app', ?::regclass, ?, ?)",
                            Boolean.class, "public." + REGIME_TABLE, column, privilege));
                    boolean should = privilege.equals("UPDATE") && REGIME_COLUMNS.contains(column);
                    if (has != should) {
                        wrong.add(privilege + " on " + column + " is " + has);
                    }
                }
            }
            assertThat(wrong).isEmpty();
            // only UPDATE is granted per column: the column ACLs hold nothing else and nobody else
            assertThat(jdbc.queryForList("SELECT DISTINCT x.privilege_type FROM pg_attribute a, aclexplode(a.attacl) x "
                    + "WHERE a.attrelid = ?::regclass AND a.attacl IS NOT NULL AND x.grantee = 'antlab_app'::regrole",
                    String.class, "public." + REGIME_TABLE)).containsExactly("UPDATE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_attribute a, aclexplode(a.attacl) x WHERE a.attrelid = ?::regclass "
                    + "AND a.attacl IS NOT NULL AND x.grantee <> 'antlab_app'::regrole AND x.grantee <> (SELECT relowner FROM pg_class "
                    + "WHERE oid = a.attrelid)", Integer.class, "public." + REGIME_TABLE)).as("no other grantee").isZero();
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
                    "UPDATE month_block SET month_start = month_start",
                    "UPDATE month_block SET event_summary = event_summary",
                    "UPDATE month_block SET events_reviewed = events_reviewed",
                    "UPDATE month_block SET created_at = created_at",
                    "UPDATE month_block SET regime_label = regime_label, month_start = month_start",
                    "INSERT INTO month_block (month_start) VALUES ('2000-01-01')",
                    "DELETE FROM month_block",
                    "TRUNCATE month_block",
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
            assertThat(sqlState(app, "SELECT * FROM month_block FOR UPDATE")).as("the label job locks the rows it fills").isEqualTo("no error");
            for (String column : REGIME_COLUMNS) {
                assertThat(sqlState(app, "UPDATE month_block SET " + column + " = " + column)).as(column).isEqualTo("no error");
            }
            assertThat(sqlState(app, "UPDATE month_block SET rate_dir = 'UP' WHERE month_start = '2000-01-01'")).isEqualTo("no error");
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
                s.execute("GRANT INSERT (name) ON asset TO antlab_app");
                s.execute("GRANT REFERENCES (name) ON asset TO antlab_app");
                s.execute("GRANT SELECT (name) ON member TO antlab_app");
                s.execute("GRANT UPDATE (month_start) ON month_block TO antlab_app");
                s.execute("GRANT INSERT (regime_label) ON month_block TO antlab_app");
                s.execute("GRANT SELECT (regime_label) ON month_block TO antlab_app");
                s.execute("GRANT UPDATE ON month_block TO antlab_app");
                s.execute("REVOKE UPDATE (prob_p1) ON month_block FROM antlab_app");
            }
            assertThat(aclSnapshot(jdbc)).isNotEqualTo(converged);

            assertThat(SERVER.migrate(db.name, "antlab_owner", true).migrationsExecuted).isZero();

            assertThat(aclSnapshot(jdbc)).isEqualTo(converged);
            for (String privilege : List.of("UPDATE", "INSERT", "REFERENCES")) {
                assertThat(jdbc.queryForObject("SELECT has_any_column_privilege('antlab_app', 'asset'::regclass, ?)",
                        Boolean.class, privilege)).as("column " + privilege).isFalse();
            }
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('antlab_app', 'month_block'::regclass, 'month_start', 'UPDATE')",
                    Boolean.class)).as("a planted right on another column is taken back").isFalse();
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('antlab_app', 'month_block'::regclass, 'prob_p1', 'UPDATE')",
                    Boolean.class)).as("a revoked regime column is granted again").isTrue();
        }
    }

    @Test
    void p8_tablesLeftBehindByASuperuserMigrationStopTheCallbackWithTheFix() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p8")) {
            prepare(db);
            db.superuserExecute("ALTER TABLE holding OWNER TO " + SERVER.container().getUsername());

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("not owned by antlab_owner")
                    .hasMessageContaining("holding").hasMessageContaining("db/roles/antlab_owner_app.sql");
        }
    }

    @Test
    void p8_aSuperuserMigrateIsNotStoppedByForeignOwnedTables() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p8s")) {
            prepare(db);
            db.superuserExecute("ALTER TABLE holding OWNER TO " + SERVER.container().getUsername());

            assertThat(SERVER.migrate(db.name, SERVER.container().getUsername(), true).success).isTrue();
        }
    }

    @Test
    void p8_aSuperuserOwnedViewOrSequenceStopsTheCallbackWithTheFixToo() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p8v")) {
            prepare(db);
            db.superuserExecute("CREATE VIEW probe_view AS SELECT 1 AS i");

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("probe_view")
                    .hasMessageContaining("db/roles/antlab_owner_app.sql");
        }
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p8q")) {
            prepare(db);
            db.superuserExecute("CREATE SEQUENCE probe_seq");

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("probe_seq");
        }
    }

    @Test
    void p9_aRoutineInPublicStopsTheCallbackAndNamesIt() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p9")) {
            prepare(db);
            try (Connection c = SERVER.connect(db.name, "antlab_owner", RoleTestServer.PASSWORD); Statement s = c.createStatement()) {
                s.execute("CREATE FUNCTION probe_routine() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
            }

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("routine(s) in public")
                    .hasMessageContaining("probe_routine");
        }
    }

    @Test
    void p11_aRightThatComesInThroughAGroupRoleOnAnotherColumnStopsTheCallbackAndNamesTheColumn() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p11")) {
            prepare(db);
            db.superuserExecute("CREATE ROLE probe_group NOLOGIN");
            db.superuserExecute("GRANT UPDATE (month_start) ON month_block TO probe_group");
            db.superuserExecute("GRANT probe_group TO antlab_app");

            assertThatThrownBy(() -> SERVER.migrate(db.name, "antlab_owner", true))
                    .isInstanceOf(FlywayException.class).hasMessageContaining("UPDATE on month_block.month_start should be denied");
        } finally {
            SERVER.execute(SERVER.container().getDatabaseName(), "DROP ROLE IF EXISTS probe_group");
        }
    }

    @Test
    void p12_theRoleScriptAcceptsTheRegimeColumnGrantOfTheCallbackAndKeepsIt() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "p12")) {
            JdbcTemplate jdbc = prepare(db);
            String converged = aclSnapshot(jdbc);

            SERVER.runRoleScript(db.name);

            assertThat(aclSnapshot(jdbc)).as("the script does not touch table or column rights").isEqualTo(converged);
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('antlab_app', 'month_block'::regclass, 'regime_label', 'UPDATE')",
                    Boolean.class)).isTrue();
        }
    }
}
