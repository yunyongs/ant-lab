package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.dbrole.RoleTestServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

/**
 * OBS-06: the statistics role antlab_metrics (db/roles/antlab_metrics.sql) on a real PostgreSQL 17 (own container, see
 * {@link RoleTestServer}). The database is built the production way: owner/app role script, Flyway as the owner with the
 * callback, then the metrics script.
 */
class AntlabMetricsRoleTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();
    private static final String DENIED = "42501";
    private static final String READ_ONLY = "25006";
    private static final String TOO_MANY = "53300";
    private static final Path QUERIES = Path.of("monitoring/postgres-exporter/queries.yaml");

    private static final String SEED = """
            INSERT INTO member (email, name, password) VALUES ('human@example.invalid', 'Human Name', '{bcrypt}SECRETHASH');
            INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count,
                    revised_count, unchanged_count, failed_target_count, error_code, error_detail)
                VALUES ('FRED_SERIES', 'MANUAL', 'PARTIAL', now(), now(), 1, 1, 0, 0, 1, 'HTTP_429', 'SECRET-ERROR-DETAIL')""";

    private static RoleTestServer.Db database(String prefix, boolean seed) throws SQLException {
        RoleTestServer.Db db = new RoleTestServer.Db(SERVER, prefix);
        try {
            db.createRoles();
            if (seed) {
                db.superuserExecute(SEED);
            }
            SERVER.runMetricsScript(db.name());
            SERVER.execute(db.name(), "ALTER ROLE antlab_metrics PASSWORD '" + RoleTestServer.PASSWORD + "'");
            return db;
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    private static Connection metrics(RoleTestServer.Db db) throws SQLException {
        return SERVER.connect(db.name(), "antlab_metrics", RoleTestServer.PASSWORD);
    }

    private static String sqlState(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            return "no error";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static List<String> relations(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT n.nspname || '.' || c.relname || '|' || c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname IN ('public', 'obs') AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S') ORDER BY 1", String.class);
    }

    /** Role, settings, memberships and every acl the script may touch, as text. */
    private static String grants(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT to_jsonb(r)::text FROM (SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, "
                        + "rolreplication, rolbypassrls, rolconnlimit FROM pg_roles WHERE rolname = 'antlab_metrics') r", String.class).toString(),
                jdbc.queryForList("SELECT setdatabase || ' ' || array_to_string(setconfig, ',') FROM pg_db_role_setting "
                        + "WHERE setrole = 'antlab_metrics'::regrole ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT roleid::regrole::text || '>' || member::regrole::text FROM pg_auth_members "
                        + "WHERE member = 'antlab_metrics'::regrole OR roleid = 'antlab_metrics'::regrole ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || '.' || c.relname || ' ' || coalesce(string_agg(x.grantee::regrole::text || '=' "
                        + "|| x.privilege_type, ',' ORDER BY x.grantee, x.privilege_type), '') "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN LATERAL aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) x "
                        + "ON x.grantee IN (0, 'antlab_metrics'::regrole) "
                        + "WHERE n.nspname IN ('obs', 'public') AND c.relkind IN ('r', 'p', 'v') "
                        + "GROUP BY n.nspname, c.relname ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || ' ' || coalesce(string_agg(x.grantee::regrole::text || '=' || x.privilege_type, "
                        + "',' ORDER BY x.grantee, x.privilege_type), '') "
                        + "FROM pg_namespace n LEFT JOIN LATERAL aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) x "
                        + "ON x.grantee IN (0, 'antlab_metrics'::regrole) "
                        + "WHERE n.nspname IN ('obs', 'public') GROUP BY n.nspname ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT coalesce(string_agg(x.grantee::regrole::text || '=' || x.privilege_type, ',' "
                        + "ORDER BY x.grantee, x.privilege_type), '') FROM pg_database d "
                        + "LEFT JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) x "
                        + "ON x.grantee IN (0, 'antlab_metrics'::regrole) WHERE d.datname = current_database()", String.class).toString());
    }

    @Test
    void roleCannotSelectAnyPublicOrObsRelation() throws SQLException {
        try (RoleTestServer.Db db = database("met1", true); Connection c = metrics(db)) {
            JdbcTemplate jdbc = db.superuser();
            List<String> relations = relations(jdbc);
            assertThat(relations).anyMatch(r -> r.equals("public.member|r")).anyMatch(r -> r.equals("public.series|r"))
                    .anyMatch(r -> r.equals("obs.job_run|v")).anyMatch(r -> r.equals("obs.agent_call|v")).anyMatch(r -> r.endsWith("|S"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(1);
            for (String entry : relations) {
                String name = entry.substring(0, entry.indexOf('|'));
                String kind = entry.substring(entry.indexOf('|') + 1);
                String read = kind.equals("S") ? "SELECT last_value FROM " + name : "SELECT count(*) FROM " + name;
                assertThat(sqlState(c, read)).as(read).isEqualTo(DENIED);
                if (!kind.equals("S")) {
                    assertThat(sqlState(c, "SELECT * FROM " + name + " LIMIT 1")).as(name).isEqualTo(DENIED);
                }
                assertThat(jdbc.queryForObject("SELECT has_table_privilege('antlab_metrics', ?::regclass, 'SELECT') "
                        + "OR has_any_column_privilege('antlab_metrics', ?::regclass, 'SELECT')", Boolean.class, name, name))
                        .as("%s privilege", name).isFalse();
            }
            assertThat(sqlState(c, "SELECT count(*) FROM public.flyway_schema_history")).isEqualTo(DENIED);
            assertThat(jdbc.queryForObject("SELECT has_schema_privilege('antlab_metrics', 'obs', 'USAGE')", Boolean.class)).isFalse();
            assertThat(sqlState(c, "SELECT count(*) FROM obs.job_run")).isEqualTo(DENIED);
        }
    }

    @Test
    void roleReadsStatisticsAndTheExporterQueriesRun() throws SQLException, IOException {
        try (RoleTestServer.Db db = database("met2", true); Connection c = metrics(db)) {
            for (String sql : List.of("SELECT count(*) FROM pg_stat_activity", "SELECT count(*) FROM pg_stat_database",
                    "SELECT count(*) FROM pg_stat_user_tables", "SELECT count(*) FROM pg_stat_bgwriter", "SELECT count(*) FROM pg_settings",
                    "SELECT pg_database_size(current_database())", "SELECT pg_total_relation_size('public.member')",
                    "SELECT count(*) FROM pg_locks", "SELECT count(*) FROM pg_roles")) {
                assertThat(sqlState(c, sql)).as(sql).isEqualTo("no error");
            }
            // pg_monitor includes pg_read_all_stats: statements of other sessions are readable, which is why the queries never select them.
            assertThat(sqlState(c, "SELECT query FROM pg_stat_activity")).isEqualTo("no error");
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT current_user, current_setting('search_path')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("antlab_metrics");
                assertThat(rs.getString(2)).isEqualTo("pg_catalog");
            }
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> queries = new Yaml().load(Files.readString(QUERIES));
            assertThat(queries).containsOnlyKeys("pg_connections", "pg_longest_transaction", "pg_table_size");
            for (var entry : queries.entrySet()) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery((String) entry.getValue().get("query"))) {
                    assertThat(rs.getMetaData().getColumnCount()).as(entry.getKey()).isPositive();
                }
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery((String) queries.get("pg_table_size").get("query"))) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString("schema") + "." + rs.getString("relation"));
                }
                assertThat(tables).contains("public.member").hasSizeLessThanOrEqualTo(10);
            }
        }
    }

    @Test
    void roleCannotWriteOrCreateAndSessionsStartReadOnly() throws SQLException {
        try (RoleTestServer.Db db = database("met3", true); Connection c = metrics(db)) {
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList("SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting WHERE setrole = 'antlab_metrics'::regrole",
                    String.class)).singleElement().asString().contains("default_transaction_read_only=on").contains("statement_timeout=10s")
                    .contains("idle_in_transaction_session_timeout=30s").contains("search_path=pg_catalog");
            assertThat(jdbc.queryForObject("SELECT rolconnlimit FROM pg_roles WHERE rolname = 'antlab_metrics'", Integer.class)).isEqualTo(3);
            assertThat(sqlState(c, "CREATE TABLE pg_temp.t0 (a int)")).isEqualTo(READ_ONLY);
            c.createStatement().execute("SET default_transaction_read_only = off");
            for (String write : List.of(
                    "INSERT INTO public.member (email, name, password) VALUES ('x@example.invalid', 'x', 'x')",
                    "UPDATE public.member SET name = 'z'",
                    "DELETE FROM public.job_run",
                    "TRUNCATE public.series_observation",
                    "CREATE TABLE public.t (a int)",
                    "CREATE TABLE obs.t (a int)",
                    "CREATE TEMP TABLE t (a int)",
                    "CREATE SCHEMA s",
                    "CREATE FUNCTION public.f() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                    "ALTER TABLE public.member ADD COLUMN z int",
                    "DROP VIEW obs.job_run",
                    "COMMENT ON TABLE public.member IS 'x'",
                    "SELECT lo_create(0)",
                    "SELECT pg_logical_emit_message(true, 'p', 'm')",
                    "SELECT pg_read_file('postgresql.conf')",
                    "SELECT pg_reload_conf()",
                    "ALTER SYSTEM SET work_mem = '1GB'",
                    "COPY public.member TO PROGRAM 'true'")) {
                assertThat(sqlState(c, write)).as(write).isEqualTo(DENIED);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void roleIsMemberOfPgMonitorOnlyAndLimitedToThreeConnections() throws SQLException {
        try (RoleTestServer.Db db = database("met4", false)) {
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList("SELECT roleid::regrole::text FROM pg_auth_members WHERE member = 'antlab_metrics'::regrole", String.class))
                    .containsExactly("pg_monitor");
            assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles "
                    + "WHERE rolname = 'antlab_metrics'", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_shdepend d WHERE d.refclassid = 'pg_authid'::regclass "
                    + "AND d.refobjid = 'antlab_metrics'::regrole AND d.deptype = 'o'", Integer.class)).isZero();
            List<Connection> open = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    open.add(metrics(db));
                }
                assertThatThrownBy(() -> metrics(db)).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(TOO_MANY));
            } finally {
                for (Connection c : open) {
                    c.close();
                }
            }
        }
    }

    @Test
    void scriptIsIdempotent() throws SQLException {
        try (RoleTestServer.Db db = database("met5", true)) {
            String first = grants(db.superuser());
            SERVER.runMetricsScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(first);
            SERVER.runMetricsScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(first);
            assertThat(first).contains("antlab_metrics").contains("pg_monitor>antlab_metrics");
            try (Connection c = metrics(db)) {
                assertThat(sqlState(c, "SELECT count(*) FROM pg_stat_database")).as("the password survives a re-run").isEqualTo("no error");
            }
        }
    }

    @Test
    void scriptConvergesAHandMadeRoleAndKeepsItsPassword() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "met6")) {
            db.createRoles();
            db.superuserExecute(SEED);
            db.superuserExecute("CREATE ROLE antlab_metrics LOGIN PASSWORD '" + RoleTestServer.PASSWORD + "'");
            db.superuserExecute("GRANT pg_read_all_data TO antlab_metrics");
            db.superuserExecute("GRANT pg_monitor TO antlab_metrics");
            db.superuserExecute("GRANT USAGE ON SCHEMA obs TO antlab_metrics");
            db.superuserExecute("GRANT SELECT ON public.member, obs.job_run TO antlab_metrics");
            db.superuserExecute("GRANT UPDATE (name) ON public.member TO antlab_metrics");
            db.superuserExecute("ALTER ROLE antlab_metrics SET statement_timeout = '0'");
            db.superuserExecute("ALTER ROLE antlab_metrics SET default_transaction_read_only = off");
            db.superuserExecute("ALTER ROLE antlab_metrics IN DATABASE " + db.name() + " SET work_mem = '1GB'");
            try (Connection before = metrics(db)) {
                assertThat(sqlState(before, "SELECT count(*) FROM public.member")).isEqualTo("no error");
            }

            SERVER.runMetricsScript(db.name());

            try (Connection after = metrics(db)) {
                assertThat(sqlState(after, "SELECT count(*) FROM public.member")).isEqualTo(DENIED);
                assertThat(sqlState(after, "SELECT count(*) FROM obs.job_run")).isEqualTo(DENIED);
                assertThat(sqlState(after, "SELECT count(*) FROM pg_stat_activity")).isEqualTo("no error");
            }
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList("SELECT roleid::regrole::text FROM pg_auth_members WHERE member = 'antlab_metrics'::regrole", String.class))
                    .containsExactly("pg_monitor");
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('antlab_metrics', 'public.member', 'name', 'UPDATE')", Boolean.class)).isFalse();
            assertThat(jdbc.queryForList("SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting WHERE setrole = 'antlab_metrics'::regrole",
                    String.class)).singleElement().asString().doesNotContain("work_mem").doesNotContain("statement_timeout=0");
        }
    }

    @Test
    void scriptRefusesUnsafeStatesAndChangesNothing() throws SQLException {
        try (RoleTestServer.Db db = database("met7", false)) {
            String before = grants(db.superuser());
            assertThatThrownBy(() -> SERVER.runScript(db.name(), null, RoleTestServer.metricsScript()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("name the target database");
            assertThatThrownBy(() -> SERVER.runScript(db.name(), "other_db", RoleTestServer.metricsScript()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("antlab.expected_database");

            db.superuserExecute("GRANT SELECT ON public.job_run TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("rolled back").hasMessageContaining("SELECT on public.job_run");
            db.superuserExecute("REVOKE SELECT ON public.job_run FROM PUBLIC");

            db.superuserExecute("GRANT USAGE ON SCHEMA obs TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("can use schema obs");
            db.superuserExecute("REVOKE USAGE ON SCHEMA obs FROM PUBLIC");

            db.superuserExecute("ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public GRANT SELECT ON TABLES TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("default privileges");
            db.superuserExecute("ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public REVOKE SELECT ON TABLES FROM PUBLIC");

            db.superuserExecute("GRANT CREATE ON SCHEMA public TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("create objects in schema \"public\"");
            db.superuserExecute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");

            db.superuserExecute("CREATE TABLE public.metrics_owned (a int)");
            db.superuserExecute("ALTER TABLE public.metrics_owned OWNER TO antlab_metrics");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("owns 1 object");
            db.superuserExecute("ALTER TABLE public.metrics_owned OWNER TO antlab_owner");
            db.superuserExecute("DROP TABLE public.metrics_owned");

            assertThat(grants(db.superuser())).isEqualTo(before);
            SERVER.runMetricsScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(before);
        }
    }

    @Test
    void scriptRefusesANonSuperuserAndDoesNotCreateARoleWhenItRefuses() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "met8")) {
            db.createRoles();
            try (Connection owner = SERVER.connect(db.name(), "antlab_owner", RoleTestServer.PASSWORD); Statement s = owner.createStatement()) {
                s.execute("SET antlab.expected_database = '" + db.name() + "'");
                assertThatThrownBy(() -> s.execute(RoleTestServer.metricsScript())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("run this script as a superuser");
            }
            db.superuserExecute("GRANT SELECT ON public.job_run TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("refusing to apply");
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'antlab_metrics'", Integer.class)).isZero();
        }
    }

    @Test
    void scriptDoesNotNeedFlywayAndRunsBeforeIt() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "met9")) {
            SERVER.runMetricsScript(db.name());
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'antlab_metrics'", Integer.class)).isOne();
            SERVER.migrate(db.name(), SERVER.container().getUsername(), false);
            SERVER.runMetricsScript(db.name());
        }
    }

    @Test
    void otherRoleScriptsAndTheMetricsScriptDoNotDisturbEachOther() throws SQLException {
        try (RoleTestServer.Db db = database("met10", true)) {
            JdbcTemplate jdbc = db.superuser();
            String metricsBefore = grants(jdbc);
            SERVER.runGrafanaScript(db.name());
            SERVER.runEngineScript(db.name());
            SERVER.runRoleScript(db.name());
            assertThat(grants(jdbc)).isEqualTo(metricsBefore);
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('grafana_reader', 'obs.job_run', 'SELECT')", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('antlab_engine', 'public.asset', 'SELECT')", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c WHERE c.relnamespace IN ('public'::regnamespace, 'obs'::regnamespace) "
                    + "AND c.relkind IN ('r', 'p', 'v', 'S') AND has_table_privilege('antlab_metrics', c.oid, 'SELECT')", Integer.class)).isZero();
            SERVER.runMetricsScript(db.name());
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('grafana_reader', 'obs.job_run', 'SELECT')", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('grafana_reader', 'public.member', 'SELECT')", Boolean.class)).isFalse();
            SERVER.runGrafanaScript(db.name());
            SERVER.runEngineScript(db.name());
        }
    }

    /** An unsafe state, the message the script must give, and how to put the database back. */
    private record Unsafe(String name, String fixture, String message, String cleanup) {}

    private static final String SEQUENCE_FIXTURE = "DO $f$ DECLARE s text; BEGIN SELECT quote_ident(n.nspname) || '.' || quote_ident(c.relname) INTO s "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'S' ORDER BY c.relname LIMIT 1; "
            + "EXECUTE 'GRANT USAGE ON SEQUENCE ' || s || ' TO PUBLIC'; END $f$";
    private static final String SEQUENCE_CLEANUP = SEQUENCE_FIXTURE.replace("GRANT USAGE ON SEQUENCE ' || s || ' TO PUBLIC", "REVOKE USAGE ON SEQUENCE ' || s || ' FROM PUBLIC");

    private static final List<Unsafe> UNSAFE = List.of(
            new Unsafe("security definer function", "CREATE FUNCTION public.obs06_sd() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
                    "SECURITY DEFINER function obs06_sd() is executable", "DROP FUNCTION public.obs06_sd()"),
            new Unsafe("extension", "CREATE EXTENSION pgcrypto", "extension \"pgcrypto\" is installed", "DROP EXTENSION pgcrypto"),
            new Unsafe("server file function", "GRANT EXECUTE ON FUNCTION pg_read_file(text) TO PUBLIC", "server-file function pg_read_file(text) is executable",
                    "REVOKE EXECUTE ON FUNCTION pg_read_file(text) FROM PUBLIC"),
            new Unsafe("server parameter", "GRANT SET ON PARAMETER work_mem TO PUBLIC", "may change server parameter \"work_mem\"",
                    "REVOKE SET ON PARAMETER work_mem FROM PUBLIC"),
            new Unsafe("foreign data wrapper", "CREATE FOREIGN DATA WRAPPER obs06_fdw; GRANT USAGE ON FOREIGN DATA WRAPPER obs06_fdw TO PUBLIC",
                    "foreign data wrapper \"obs06_fdw\"", "DROP FOREIGN DATA WRAPPER obs06_fdw"),
            new Unsafe("foreign server", "CREATE FOREIGN DATA WRAPPER obs06_fdw; CREATE SERVER obs06_srv FOREIGN DATA WRAPPER obs06_fdw; "
                    + "GRANT USAGE ON FOREIGN SERVER obs06_srv TO PUBLIC", "foreign server \"obs06_srv\"",
                    "DROP SERVER obs06_srv; DROP FOREIGN DATA WRAPPER obs06_fdw"),
            new Unsafe("database create", "DO $f$ BEGIN EXECUTE format('GRANT CREATE ON DATABASE %I TO PUBLIC', current_database()); END $f$",
                    "create schemas or temporary tables", "DO $f$ BEGIN EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC', current_database()); END $f$"),
            new Unsafe("sequence from PUBLIC", SEQUENCE_FIXTURE, "USAGE on sequence public.", SEQUENCE_CLEANUP),
            new Unsafe("column grant from PUBLIC", "GRANT SELECT (id) ON public.member TO PUBLIC", "SELECT on public.member",
                    "REVOKE SELECT (id) ON public.member FROM PUBLIC"),
            new Unsafe("default privilege to pg_monitor", "ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public GRANT SELECT ON TABLES TO pg_monitor",
                    "default privileges of antlab_owner grant on object type \"r\" to pg_monitor",
                    "ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public REVOKE SELECT ON TABLES FROM pg_monitor"),
            new Unsafe("default privilege to a parent of pg_monitor", "ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public GRANT SELECT ON TABLES TO pg_read_all_stats",
                    "to pg_read_all_stats", "ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public REVOKE SELECT ON TABLES FROM pg_read_all_stats"),
            new Unsafe("pg_monitor inherits from a data role", "GRANT pg_read_all_data TO pg_monitor", "inherits from role \"pg_read_all_data\"",
                    "REVOKE pg_read_all_data FROM pg_monitor"));

    private static void refuse(RoleTestServer.Db db, Unsafe u) {
        db.superuserExecute(u.fixture());
        try {
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).as(u.name()).isInstanceOf(SQLException.class)
                    .hasMessageContaining("rolled back").hasMessageContaining(u.message());
        } finally {
            db.superuserExecute(u.cleanup());
        }
    }

    @Test
    void everyUnsafeStateIsRefusedAndCreatesNoRole() throws SQLException {
        // Roles are cluster-wide: this pass runs while no antlab_metrics exists.
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "met12")) {
            db.createRoles();
            for (Unsafe u : UNSAFE) {
                refuse(db, u);
                assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'antlab_metrics'", Integer.class))
                        .as(u.name() + " created no role").isZero();
            }
            SERVER.runMetricsScript(db.name());
        }
    }

    @Test
    void everyUnsafeStateIsRefusedAndChangesNothingOfAnExistingRole() throws SQLException {
        try (RoleTestServer.Db db = database("met11", false)) {
            String before = grants(db.superuser());
            for (Unsafe u : UNSAFE) {
                refuse(db, u);
                assertThat(grants(db.superuser())).as(u.name() + " changed nothing").isEqualTo(before);
            }
            SERVER.runMetricsScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(before);
        }
    }

    @Test
    void aTableCreatedAfterTheScriptIsNotReadableAndADefaultGrantToPgMonitorIsRefused() throws SQLException {
        try (RoleTestServer.Db db = database("met13", false); Connection c = metrics(db)) {
            db.superuserExecute("SET ROLE antlab_owner; CREATE TABLE public.obs06_later (a int); CREATE VIEW public.obs06_later_v AS SELECT 1 AS a");
            assertThat(sqlState(c, "SELECT count(*) FROM public.obs06_later")).isEqualTo(DENIED);
            assertThat(sqlState(c, "SELECT count(*) FROM public.obs06_later_v")).isEqualTo(DENIED);

            db.superuserExecute("ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA public GRANT SELECT ON TABLES TO pg_monitor");
            assertThatThrownBy(() -> SERVER.runMetricsScript(db.name())).isInstanceOf(SQLException.class).hasMessageContaining("to pg_monitor");
            // Without the check the next table would be readable: this is the path the refusal closes.
            db.superuserExecute("SET ROLE antlab_owner; CREATE TABLE public.obs06_leak (a int)");
            assertThat(sqlState(c, "SELECT count(*) FROM public.obs06_leak")).isEqualTo("no error");
        }
    }
}
