package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.dbrole.RoleTestServer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OBS-01: schema obs and the role grafana_reader on a real PostgreSQL 17 (own container, see {@link RoleTestServer}). The
 * database is built the production way: owner/app role script, Flyway as the owner with the callback, reader script.
 */
class ObsViewsGrantTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();
    private static final String DENIED = "42501";
    private static final String READ_ONLY = "25006";

    /** The boundary table of the plan, section 5.1: the full column list of every view, in order. */
    private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
            Map.entry("job_run", List.of("id", "job_name", "run_trigger", "status", "started_at", "finished_at", "duration_s",
                    "request_count", "inserted_count", "revised_count", "unchanged_count", "failed_target_count", "error_code")),
            Map.entry("series_freshness", List.of("source", "series_key", "frequency", "last_observation_date",
                    "observation_count", "last_collected_at")),
            Map.entry("price_freshness", List.of("market", "source", "last_price_date", "asset_count")),
            Map.entry("agent", List.of("agent_id", "persona_key", "model_route", "model_id", "status", "created_at")),
            Map.entry("agent_season", List.of("season_id", "agent_id", "persona_key", "persona_version", "model_route",
                    "model_requested", "status", "path_length", "current_turn_no", "budget_calls", "budget_usd", "memory_version",
                    "runner_version", "prompt_template_version", "created_at", "updated_at")),
            Map.entry("agent_call", List.of("id", "season_id", "turn_no", "attempt_no", "call_kind", "status", "http_status",
                    "model_requested", "model_actual", "provider", "tokens_in", "tokens_out", "cost_usd", "latency_ms", "is_final",
                    "outcome", "reject_count", "created_at")),
            Map.entry("agent_call_reject", List.of("season_id", "turn_no", "reject_code")),
            Map.entry("agent_order", List.of("season_id", "turn_no", "side", "type", "status", "source", "created_at")),
            Map.entry("agent_turn", List.of("season_id", "turn_no", "total_value", "drawdown", "created_at")),
            Map.entry("agent_season_score", List.of("season_id", "agent_id", "persona_key", "model_requested", "score_version",
                    "score", "result_score", "process_score", "rating_exclusion", "created_at")),
            Map.entry("season_summary", List.of("kind", "status", "path_length", "season_count")));

    /** Base columns no view may read, directly or through a join or an expression (D10 and the free-text rule). */
    private static final Set<String> FORBIDDEN_BASE = Set.of(
            "agent_call.request_text", "agent_call.response_text", "agent_call.parsed", "agent_call.request_sha256",
            "agent_call.ledger_call_id", "job_run.error_detail", "season_score.inputs", "member.email", "member.password",
            "member.name", "season.seed", "season.base_date", "season.data_snapshot_date", "season.engine_version",
            "season_agent_config.persona_sha256", "season_agent_config.model_seed", "orders.asset_id", "orders.quantity",
            "orders.limit_price", "asset.ticker", "asset.name");

    private static final List<String> SECRETS = List.of("SECRET-PROMPT", "SECRET-REPLY", "SECRET-PARSED", "SECRET-ERROR-DETAIL",
            "SECRET-POINTER", "SECRET-CODE", "SECRET-HIDDEN", "SECRET_REPLY", "SECRETHASH", "human@example.invalid", "2020-01-31");

    private static final String SEED = """
            DO $seed$
            DECLARE
                h bigint; a bigint; a2 bigint; hs bigint; ags bigint; ags2 bigint; g bigint; ast bigint; jr bigint; jr2 bigint; ser bigint;
            BEGIN
                INSERT INTO member (email, name, password) VALUES ('human@example.invalid', 'Human Name', '{bcrypt}SECRETHASH')
                    RETURNING id INTO h;
                INSERT INTO member (kind, name) VALUES ('AGENT', 'agent-a') RETURNING id INTO a;
                INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id)
                    VALUES (a, h, 'calm-value', 'OPENROUTER', 'vendor/model-a');
                INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash,
                        fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder,
                        start_forward, current_goalkeeper, current_defender, current_midfielder, current_forward)
                    VALUES (h, 12, 777, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20) RETURNING id INTO hs;
                INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash,
                        fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder,
                        start_forward, current_goalkeeper, current_defender, current_midfielder, current_forward)
                    VALUES (a, 12, 778, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20) RETURNING id INTO ags;
                INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, persona_sha256, model_route,
                        model_requested, temperature, max_tokens, turn_timeout_ms, prompt_template_version, runner_version,
                        budget_calls, budget_usd)
                    VALUES (ags, a, 'calm-value', 1, repeat('a', 64), 'OPENROUTER', 'vendor/model-a', 0.2, 2000, 60000,
                        'p1', 'r1', 100, 1.5);
                INSERT INTO member (kind, name) VALUES ('AGENT', 'agent-b') RETURNING id INTO a2;
                INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id)
                    VALUES (a2, h, 'other-agent', 'OLLAMA', 'vendor/model-b');
                INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash,
                        fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder,
                        start_forward, current_goalkeeper, current_defender, current_midfielder, current_forward)
                    VALUES (a2, 12, 779, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20) RETURNING id INTO ags2;
                INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, persona_sha256, model_route,
                        model_requested, temperature, max_tokens, turn_timeout_ms, prompt_template_version, runner_version,
                        budget_calls, budget_usd)
                    VALUES (ags2, a2, 'other-agent', 1, repeat('b', 64), 'OLLAMA', 'vendor/model-b', 0.2, 2000, 60000,
                        'p1', 'r1', 100, 1.5);
                INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value,
                        midfielder_value, forward_value, drawdown)
                    VALUES (hs, 1, 1000000, 11345678, 12345678, 1000000, 4000000, 4000000, 3345678, -0.01),
                           (ags, 1, 4000000, 6100000, 10100000, 4000000, 2000000, 2000000, 2100000, -0.02),
                           (ags2, 1, 5000000, 555555, 5555555, 5000000, 0, 0, 555555, -0.03);
                INSERT INTO asset_group (code, name, kind) VALUES ('G1', 'group', 'SECTOR') RETURNING id INTO g;
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                    VALUES ('KR', 'T1', 'Asset Name', 'STOCK', 'KRW', g, '2000-01-01', 'PRICE') RETURNING id INTO ast;
                INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
                    VALUES (hs, ast, 1, 'BUY', 7, 'FILLED', 'DIRECT'), (ags, ast, 1, 'SELL', 3, 'REJECTED', 'DIRECT'),
                           (ags2, ast, 1, 'BUY', 9, 'PENDING', 'DIRECT');
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count,
                        revised_count, unchanged_count, failed_target_count, error_code, error_detail)
                    VALUES ('FRED_SERIES', 'MANUAL', 'PARTIAL', '2020-06-01 09:00:00+09', '2020-06-01 09:00:05+09', 12, 30, 1, 5, 1,
                        'HTTP_429', 'SECRET-ERROR-DETAIL') RETURNING id INTO jr;
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count,
                        revised_count, unchanged_count, failed_target_count)
                    VALUES ('KIS_DAILY_PRICE', 'SCHEDULED', 'SUCCEEDED', '2020-06-02 09:00:00+09', '2020-06-02 09:01:00+09', 3, 3, 0, 0, 0)
                    RETURNING id INTO jr2;
                INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                    VALUES ('CPI', 'FRED', 'CPIAUCSL', 'INDEX', 'MONTHLY', 10, true) RETURNING id INTO ser;
                INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                    VALUES ('EMPTY_SERIES', 'ECOS', 'X1', 'INDEX', 'DAILY', 1, false);
                INSERT INTO series_observation (series_id, ref_date, value, observed_at, vintage_no, job_run_id)
                    VALUES (ser, '2020-01-01', 1.5, '2020-06-01 09:00:00+09', 1, jr),
                           (ser, '2020-01-01', 1.6, '2020-06-01 09:00:01+09', 2, jr),
                           (ser, '2020-02-01', 1.7, '2020-06-01 09:00:02+09', 1, jr);
                INSERT INTO asset_daily_price (asset_id, trade_date, price_basis, source, close_price, vintage_no, observed_at, job_run_id)
                    VALUES (ast, '2020-03-02', 'RAW', 'KIS', 70000, 1, '2020-03-03 10:00:00+09', jr2);
                INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text,
                        response_text, tokens_in, tokens_out, cost_usd, latency_ms)
                    VALUES (ags, 1, 1, 'TURN_DECISION', 'INVALID', 'vendor/model-a', 'SECRET-PROMPT-1', 'SECRET-REPLY-1', 100, 20,
                        0.00123456, 800);
                INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, http_status, model_requested, model_actual,
                        provider, request_text, response_text, parsed, tokens_in, tokens_out, cost_usd, latency_ms, is_final,
                        outcome, rejects, request_sha256)
                    VALUES (ags, 1, 2, 'TURN_DECISION', 'OK', 200, 'vendor/model-a', 'vendor/model-a-2025', 'ProviderX',
                        'SECRET-PROMPT-2', 'SECRET-REPLY-2', '{"x":"SECRET-PARSED"}', 120, 30, 0.00200000, 900, true, 'DECISION',
                        '[{"unit":"ORDER","index":0,"code":"QUANTITY_INVALID","pointer":null},
                          {"unit":null,"index":null,"code":"free text SECRET-CODE","pointer":"/orders/0/SECRET-POINTER"},
                          {"unit":"ORDER","index":1,"code":"QUANTITY_INVALID","pointer":null},
                          {"unit":"ORDER","index":2,"code":"SECRET_REPLY","pointer":null}]'::jsonb,
                        repeat('a', 64));
                INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text,
                        tokens_in, tokens_out, cost_usd, latency_ms, rejects)
                    VALUES (ags2, 1, 1, 'TURN_DECISION', 'OK', 'vendor/model-b', 'SECRET-PROMPT-HIDDEN', 777, 1, 9.99, 5, NULL);
                INSERT INTO season_score (season_id, member_id, score_version, score, result_score, process_score, sharpe,
                        bench_sharpe, sharpe_diff, sharpe_diff_percentile, luck_percentile, risk_score, process_items, inputs)
                    VALUES (ags, a, 'v1', 70, 70, 60, 1, 0.5, 0.5, 0.5, 0.5, 60, 1, '{"secret":"SECRET-PARSED"}'),
                           (hs, h, 'v1', 55, 55, 50, 1, 0.5, 0.5, 0.5, 0.5, 50, 1, '{"secret":"SECRET-PARSED"}'),
                           (ags2, a2, 'v1', 33, 33, 30, 1, 0.5, 0.5, 0.5, 0.5, 30, 1, '{}');
                UPDATE season SET status = 'FINISHED', current_turn_no = 12 WHERE id IN (hs, ags);
                UPDATE season SET current_turn_no = 0 WHERE id = ags2;
            END
            $seed$""";

    private static RoleTestServer.Db database(String prefix, boolean seed) throws SQLException {
        RoleTestServer.Db db = new RoleTestServer.Db(SERVER, prefix);
        try {
            db.createRoles();
            if (seed) {
                db.superuserExecute(SEED);
            }
            SERVER.runGrafanaScript(db.name());
            SERVER.execute(db.name(), "ALTER ROLE grafana_reader PASSWORD '" + RoleTestServer.PASSWORD + "'");
            return db;
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    private static RoleTestServer.Db plainDatabase(String prefix) throws SQLException {
        RoleTestServer.Db db = new RoleTestServer.Db(SERVER, prefix);
        try {
            db.createRoles();
            return db;
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    private static Connection reader(RoleTestServer.Db db) throws SQLException {
        return SERVER.connect(db.name(), "grafana_reader", RoleTestServer.PASSWORD);
    }

    private static String sqlState(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            return "no error";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static List<String> viewNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'obs'::regnamespace ORDER BY relname", String.class);
    }

    /** Owners, schema, relation and column ACLs of obs and public, and the settings of every antlab_* role: independent of grafana_reader. */
    private static String aclSnapshot(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT nspname || ' ' || nspowner::regrole || ' ' || coalesce(nspacl::text, 'default') FROM pg_namespace "
                        + "WHERE nspname IN ('obs', 'public') ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || '.' || c.relname || ' ' || c.relowner::regrole || ' ' || coalesce(c.relacl::text, 'default') "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname IN ('obs', 'public') "
                        + "AND c.relkind IN ('r', 'p', 'v', 'S') ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || '.' || c.relname || '.' || a.attname || ' ' || a.attacl::text FROM pg_attribute a "
                        + "JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname IN ('obs', 'public') AND a.attacl IS NOT NULL ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT setrole::regrole || ' ' || setdatabase || ' ' || array_to_string(setconfig, ',') "
                        + "FROM pg_db_role_setting WHERE setrole <> 0 ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT rolname || ' ' || rolconnlimit FROM pg_roles WHERE rolname LIKE 'antlab\\_%' OR rolname = 'grafana_reader' "
                        + "ORDER BY 1", String.class).toString());
    }

    /** Roles, settings, memberships and every acl the script may touch, as text. */
    private static String grants(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT to_jsonb(r)::text FROM (SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, "
                        + "rolreplication, rolbypassrls, rolconnlimit FROM pg_roles WHERE rolname = 'grafana_reader') r",
                        String.class).toString(),
                jdbc.queryForList("SELECT setdatabase || ' ' || array_to_string(setconfig, ',') FROM pg_db_role_setting "
                        + "WHERE setrole = 'grafana_reader'::regrole ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT roleid::regrole::text || '>' || member::regrole::text FROM pg_auth_members "
                        + "WHERE member = 'grafana_reader'::regrole OR roleid = 'grafana_reader'::regrole ORDER BY 1",
                        String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || '.' || c.relname || ' ' || coalesce(string_agg(x.grantee::regrole::text || '=' "
                        + "|| x.privilege_type, ',' ORDER BY x.grantee, x.privilege_type), '') "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN LATERAL aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) x "
                        + "ON x.grantee IN (0, 'grafana_reader'::regrole) "
                        + "WHERE n.nspname IN ('obs', 'public') AND c.relkind IN ('r', 'p', 'v') "
                        + "GROUP BY n.nspname, c.relname ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT n.nspname || ' ' || coalesce(string_agg(x.grantee::regrole::text || '=' || x.privilege_type, "
                        + "',' ORDER BY x.grantee, x.privilege_type), '') "
                        + "FROM pg_namespace n LEFT JOIN LATERAL aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) x "
                        + "ON x.grantee IN (0, 'grafana_reader'::regrole) "
                        + "WHERE n.nspname IN ('obs', 'public') GROUP BY n.nspname ORDER BY 1", String.class).toString(),
                jdbc.queryForList("SELECT coalesce(string_agg(x.grantee::regrole::text || '=' || x.privilege_type, ',' "
                        + "ORDER BY x.grantee, x.privilege_type), '') FROM pg_database d "
                        + "LEFT JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) x "
                        + "ON x.grantee IN (0, 'grafana_reader'::regrole) WHERE d.datname = current_database()",
                        String.class).toString());
    }

    @Test
    void readerReadsEveryViewAndSeesOnlyAgentRows() throws SQLException {
        try (RoleTestServer.Db db = database("obs1", true); Connection c = reader(db)) {
            assertThat(viewNames(db.superuser())).containsExactlyInAnyOrderElementsOf(COLUMNS.keySet());
            Map<String, Integer> counts = new TreeMap<>();
            for (String view : COLUMNS.keySet()) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM obs." + view)) {
                    rs.next();
                    counts.put(view, rs.getInt(1));
                }
            }
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("job_run", 2), Map.entry("series_freshness", 2), Map.entry("price_freshness", 1),
                    Map.entry("agent", 2), Map.entry("agent_season", 2), Map.entry("agent_call", 2),
                    Map.entry("agent_call_reject", 4), Map.entry("agent_order", 1), Map.entry("agent_turn", 1),
                    Map.entry("agent_season_score", 1), Map.entry("season_summary", 3)));
            JdbcTemplate jdbc = SERVER.jdbc(db.name(), "grafana_reader", RoleTestServer.PASSWORD);
            assertThat(jdbc.queryForObject("SELECT total_value FROM obs.agent_turn", java.math.BigDecimal.class))
                    .isEqualByComparingTo("10100000");
            assertThat(jdbc.queryForObject("SELECT side FROM obs.agent_order", String.class)).isEqualTo("SELL");
            assertThat(jdbc.queryForObject("SELECT score FROM obs.agent_season_score", java.math.BigDecimal.class))
                    .isEqualByComparingTo("70");
            assertThat(jdbc.queryForList("SELECT kind || ':' || status || ':' || path_length || ':' || season_count "
                    + "FROM obs.season_summary ORDER BY 1", String.class))
                    .containsExactly("AGENT:FINISHED:12:1", "AGENT:IN_PROGRESS:12:1", "HUMAN:FINISHED:12:1");
            // the two public macro tables stay readable (owner decision D2)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.series_observation", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM series", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void viewValuesAreDerivedAsPlanned() throws SQLException {
        try (RoleTestServer.Db db = database("obs2", true)) {
            JdbcTemplate jdbc = SERVER.jdbc(db.name(), "grafana_reader", RoleTestServer.PASSWORD);
            assertThat(jdbc.queryForObject("SELECT duration_s FROM obs.job_run WHERE job_name = 'FRED_SERIES'",
                    java.math.BigDecimal.class)).isEqualByComparingTo("5");
            assertThat(jdbc.queryForObject("SELECT duration_s::text FROM obs.job_run WHERE job_name = 'FRED_SERIES'", String.class))
                    .as("duration keeps millisecond scale").isEqualTo("5.000");
            assertThat(jdbc.queryForObject("SELECT error_code FROM obs.job_run WHERE job_name = 'FRED_SERIES'", String.class))
                    .isEqualTo("HTTP_429");
            assertThat(jdbc.queryForList("SELECT series_key || ':' || coalesce(last_observation_date::text, '-') || ':' "
                    + "|| observation_count FROM obs.series_freshness ORDER BY 1", String.class))
                    .containsExactly("CPI:2020-02-01:2", "EMPTY_SERIES:-:0");
            assertThat(jdbc.queryForObject("SELECT market || ':' || source || ':' || last_price_date || ':' || asset_count "
                    + "FROM obs.price_freshness", String.class)).isEqualTo("KR:KIS:2020-03-02:1");
            assertThat(jdbc.queryForList("SELECT attempt_no || ':' || reject_count FROM obs.agent_call ORDER BY attempt_no",
                    String.class)).containsExactly("1:0", "2:4");
            assertThat(jdbc.queryForList("SELECT reject_code FROM obs.agent_call_reject ORDER BY reject_code", String.class))
                    .as("a code outside the allowlist never leaves the view").containsExactly("OTHER", "OTHER", "QUANTITY_INVALID", "QUANTITY_INVALID");
            assertThat(jdbc.queryForObject("SELECT cost_usd FROM obs.agent_call WHERE attempt_no = 1", java.math.BigDecimal.class))
                    .isEqualByComparingTo("0.00123456");
            assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = 'obs' "
                    + "AND table_name = 'agent_call' AND column_name = 'cost_usd'", String.class)).isEqualTo("numeric");
            // no human season in any agent view: the human order, turn and score are absent
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_order WHERE side = 'BUY'", Integer.class)).isZero();
            // rows of an agent season that has not reached turn 1 (and its unfinished score) stay hidden
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_turn WHERE total_value = 5555555", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_call WHERE tokens_in = 777", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_season_score WHERE score = 33", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_season WHERE current_turn_no = 0", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_turn WHERE total_value = 12345678", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM obs.agent_season_score WHERE score = 55", Integer.class)).isZero();
        }
    }

    @Test
    void noViewCarriesTextOrDatesOfSeasonsOrSecrets() throws SQLException {
        try (RoleTestServer.Db db = database("obs3", true); Connection c = reader(db)) {
            StringBuilder everything = new StringBuilder();
            for (String view : COLUMNS.keySet()) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM obs." + view)) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            everything.append(rs.getString(i)).append('|');
                        }
                    }
                }
            }
            for (String secret : SECRETS) {
                assertThat(everything.toString()).as("value %s must not reach any obs view", secret).doesNotContain(secret);
            }
            // base columns the views depend on, read from the catalog (joins and expressions included)
            JdbcTemplate jdbc = db.superuser();
            List<String> used = jdbc.queryForList("""
                    SELECT DISTINCT c.relname || '.' || a.attname
                    FROM pg_class v
                    JOIN pg_rewrite rw ON rw.ev_class = v.oid
                    JOIN pg_depend d ON d.objid = rw.oid AND d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
                    JOIN pg_class c ON c.oid = d.refobjid AND c.relnamespace = 'public'::regnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.refobjsubid
                    WHERE v.relnamespace = 'obs'::regnamespace AND d.refobjsubid > 0""", String.class);
            assertThat(used).isNotEmpty().doesNotContainAnyElementsOf(FORBIDDEN_BASE);
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT c.relname
                    FROM pg_class v
                    JOIN pg_rewrite rw ON rw.ev_class = v.oid
                    JOIN pg_depend d ON d.objid = rw.oid AND d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
                    JOIN pg_class c ON c.oid = d.refobjid AND c.relkind = 'r'
                    WHERE v.relnamespace = 'obs'::regnamespace ORDER BY 1""", String.class))
                    .as("season_path, month_block and the history tables are not read at all")
                    .containsExactlyInAnyOrder("agent_call", "agent_profile", "asset", "asset_daily_price", "job_run", "member",
                            "orders", "season", "season_agent_config", "season_score", "series", "series_observation", "turn");
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT v.relname FROM pg_class v
                    JOIN pg_rewrite rw ON rw.ev_class = v.oid
                    JOIN pg_depend d ON d.objid = rw.oid AND d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
                    JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
                    WHERE v.relnamespace = 'obs'::regnamespace AND d.refobjid = 'public.agent_call'::regclass
                      AND a.attname = 'rejects' ORDER BY 1""", String.class))
                    .containsExactly("agent_call", "agent_call_reject");
        }
    }

    @Test
    void viewColumnsEqualThePlanTableAndTheViewsAreBarriersOwnedByTheOwner() throws SQLException {
        try (RoleTestServer.Db db = database("obs4", false)) {
            JdbcTemplate jdbc = db.superuser();
            for (Map.Entry<String, List<String>> view : COLUMNS.entrySet()) {
                assertThat(jdbc.queryForList("SELECT a.attname FROM pg_attribute a WHERE a.attrelid = ?::regclass AND a.attnum > 0 "
                        + "AND NOT a.attisdropped ORDER BY a.attnum", String.class, "obs." + view.getKey()))
                        .as("columns of obs.%s", view.getKey()).containsExactlyElementsOf(view.getValue());
            }
            List<String> forbiddenNames = List.of("request_text", "response_text", "parsed", "rejects", "email", "password",
                    "error_detail", "detail", "inputs", "season_path", "month_block", "base_date", "data_snapshot_date", "seed");
            assertThat(jdbc.queryForList("SELECT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                    + "WHERE c.relnamespace = 'obs'::regnamespace AND a.attnum > 0 AND NOT a.attisdropped", String.class))
                    .doesNotContainAnyElementsOf(forbiddenNames);
            assertThat(jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'obs'::regnamespace AND relkind <> 'v'",
                    String.class)).as("schema obs holds views only").isEmpty();
            assertThat(jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'obs'::regnamespace AND relkind = 'v' "
                    + "AND NOT coalesce(reloptions, '{}') @> ARRAY['security_barrier=true']", String.class))
                    .as("every view is a security barrier").isEmpty();
            assertThat(jdbc.queryForList("SELECT DISTINCT relowner::regrole::text FROM pg_class WHERE relnamespace = 'obs'::regnamespace",
                    String.class)).containsExactly("antlab_owner");
            assertThat(jdbc.queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'obs'", String.class))
                    .isEqualTo("antlab_owner");
            assertThat(jdbc.queryForList("SELECT c.relname || '.' || a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                    + "WHERE c.relnamespace = 'obs'::regnamespace AND a.attnum > 0 AND NOT a.attisdropped "
                    + "AND a.atttypid IN ('float4'::regtype, 'float8'::regtype)", String.class))
                    .as("no floating-point column in any view").isEmpty();
            assertThat(jdbc.queryForList("SELECT c.relname || '.' || a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                    + "WHERE c.relnamespace = 'obs'::regnamespace AND a.attnum > 0 AND NOT a.attisdropped "
                    + "AND a.attname IN ('total_value', 'drawdown', 'cost_usd', 'budget_usd', 'score', 'result_score', 'process_score', 'duration_s') "
                    + "AND a.atttypid <> 'numeric'::regtype", String.class)).as("money, score and duration columns are numeric").isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc WHERE pronamespace = 'obs'::regnamespace", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForList("SELECT c.relname FROM pg_class c, aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) x "
                    + "WHERE c.relnamespace = 'obs'::regnamespace AND x.grantee NOT IN (c.relowner, 'grafana_reader'::regrole) "
                    + "ORDER BY 1", String.class)).as("nobody but the owner and the reader holds a right on a view").isEmpty();
            assertThat(jdbc.queryForObject("SELECT has_schema_privilege('antlab_app', 'obs', 'USAGE')", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT has_schema_privilege('antlab_owner', 'obs', 'CREATE')", Boolean.class)).isTrue();
        }
    }

    @Test
    void readerCannotTouchAnythingElse() throws SQLException {
        try (RoleTestServer.Db db = database("obs5", true); Connection c = reader(db)) {
            for (String table : List.of("agent_call", "season_path", "member", "season", "orders", "turn", "job_run",
                    "season_agent_config", "agent_profile", "season_score", "asset", "month_block", "asset_daily_price",
                    "flyway_schema_history", "refresh_token", "cash_ledger")) {
                assertThat(sqlState(c, "SELECT count(*) FROM public." + table)).as("public.%s", table).isEqualTo(DENIED);
            }
            assertThat(sqlState(c, "SELECT request_text FROM public.agent_call")).isEqualTo(DENIED);
            assertThat(sqlState(c, "SELECT response_text, parsed, rejects FROM public.agent_call")).isEqualTo(DENIED);
            assertThat(sqlState(c, "SELECT * FROM job_run")).as("unqualified job_run resolves to the view").isEqualTo("no error");
            assertThat(sqlState(c, "SELECT count(*) FROM public.job_run")).isEqualTo(DENIED);
            assertThat(sqlState(c, "SELECT count(*) FROM public.series")).isEqualTo("no error");
        }
    }

    @Test
    void readerCannotWriteOrCreateAndSessionsStartReadOnly() throws SQLException {
        try (RoleTestServer.Db db = database("obs6", true); Connection c = reader(db)) {
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList("SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting WHERE setrole = 'grafana_reader'::regrole",
                    String.class)).singleElement().asString().contains("default_transaction_read_only=on")
                    .contains("statement_timeout=30s").contains("search_path=obs, public");
            assertThat(jdbc.queryForObject("SELECT rolconnlimit FROM pg_roles WHERE rolname = 'grafana_reader'", Integer.class)).isEqualTo(10);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW default_transaction_read_only")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("on");
            }
            assertThat(sqlState(c, "INSERT INTO public.series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                    + "VALUES ('X', 'FRED', 'x', 'U', 'DAILY', 0, true)")).isEqualTo(READ_ONLY);

            // a session that turns read-only off still has no right to write or create
            c.createStatement().execute("SET default_transaction_read_only = off");
            for (String write : List.of(
                    "INSERT INTO public.series (code, source, source_series_id, unit, frequency, release_lag_days, revisable) "
                            + "VALUES ('X', 'FRED', 'x', 'U', 'DAILY', 0, true)",
                    "UPDATE public.series SET unit = 'Z'",
                    "DELETE FROM public.series_observation",
                    "TRUNCATE public.series_observation",
                    "CREATE TABLE public.t (a int)",
                    "CREATE TABLE obs.t (a int)",
                    "CREATE VIEW obs.v AS SELECT 1",
                    "CREATE TEMP TABLE t (a int)",
                    "CREATE SCHEMA s",
                    "CREATE FUNCTION public.f() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                    "ALTER TABLE public.series ADD COLUMN z int",
                    "DROP VIEW obs.job_run",
                    "COMMENT ON TABLE public.series IS 'x'",
                    "SELECT lo_create(0)",
                    "SELECT pg_logical_emit_message(true, 'p', 'm')")) {
                assertThat(sqlState(c, write)).as(write).isEqualTo(DENIED);
            }
            // views: a plain view is refused for lack of a right, a join view or a non-table is refused as not updatable
            for (String write : List.of(
                    "INSERT INTO obs.agent (persona_key) VALUES ('x')",
                    "UPDATE obs.agent_call SET status = 'OK'",
                    "DELETE FROM obs.job_run",
                    "DELETE FROM obs.agent_turn",
                    "TRUNCATE obs.agent_turn")) {
                assertThat(sqlState(c, write)).as(write).isIn(DENIED, "55000", "42809");
            }
            assertThat(sqlState(c, "DELETE FROM obs.job_run")).isEqualTo(DENIED);
            assertThat(sqlState(c, "INSERT INTO obs.agent (persona_key) VALUES ('x')")).isEqualTo(DENIED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM series", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void scriptIsIdempotent() throws SQLException {
        try (RoleTestServer.Db db = database("obs7", true)) {
            String first = grants(db.superuser());
            SERVER.runGrafanaScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(first);
            SERVER.runGrafanaScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(first);
            assertThat(first).contains("grafana_reader").contains("obs.job_run");
            try (Connection c = reader(db)) {
                assertThat(sqlState(c, "SELECT 1 FROM obs.agent_turn")).as("the password survives a re-run").isEqualTo("no error");
            }
        }
    }

    @Test
    void scriptConvergesTheHandMadeRoleAndKeepsItsPassword() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs8")) {
            db.createRoles();
            db.superuserExecute(SEED);
            // as created by hand on the owner's database: login, SELECT on the two macro tables, and then some drift
            db.superuserExecute("CREATE ROLE grafana_reader LOGIN PASSWORD '" + RoleTestServer.PASSWORD + "'");
            db.superuserExecute("GRANT USAGE ON SCHEMA public TO grafana_reader");
            db.superuserExecute("GRANT SELECT ON public.series, public.series_observation TO grafana_reader");
            db.superuserExecute("GRANT SELECT ON public.member, public.agent_call TO grafana_reader");
            db.superuserExecute("GRANT UPDATE (unit) ON public.series TO grafana_reader");
            db.superuserExecute("GRANT pg_read_all_data TO grafana_reader");
            db.superuserExecute("ALTER ROLE grafana_reader SET statement_timeout = '0'");
            db.superuserExecute("ALTER ROLE grafana_reader SET default_transaction_read_only = off");
            db.superuserExecute("ALTER ROLE grafana_reader IN DATABASE " + db.name() + " SET work_mem = '1GB'");
            try (Connection before = reader(db)) {
                assertThat(sqlState(before, "SELECT count(*) FROM public.member")).isEqualTo("no error");
            }

            SERVER.runGrafanaScript(db.name());

            try (Connection after = reader(db)) {
                assertThat(sqlState(after, "SELECT count(*) FROM public.member")).isEqualTo(DENIED);
                assertThat(sqlState(after, "SELECT count(*) FROM public.agent_call")).isEqualTo(DENIED);
                assertThat(sqlState(after, "SELECT count(*) FROM public.series")).isEqualTo("no error");
                assertThat(sqlState(after, "SELECT count(*) FROM obs.agent_call")).isEqualTo("no error");
                assertThat(sqlState(after, "UPDATE public.series SET unit = unit")).isIn(READ_ONLY, DENIED);
            }
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('grafana_reader', 'public.series', 'unit', 'UPDATE')", Boolean.class))
                    .isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member = 'grafana_reader'::regrole", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForList("SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting WHERE setrole = 'grafana_reader'::regrole",
                    String.class)).singleElement().asString().doesNotContain("work_mem").doesNotContain("statement_timeout=0");
        }
    }

    @Test
    void scriptRefusesUnsafeStatesAndChangesNothing() throws SQLException {
        try (RoleTestServer.Db db = database("obs9", false)) {
            String before = grants(db.superuser());
            assertThatThrownBy(() -> SERVER.runScript(db.name(), null, RoleTestServer.grafanaScript()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("name the target database");
            assertThatThrownBy(() -> SERVER.runScript(db.name(), "other_db", RoleTestServer.grafanaScript()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("antlab.expected_database");

            db.superuserExecute("GRANT INSERT ON obs.job_run TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("rolled back").hasMessageContaining("obs.job_run");
            db.superuserExecute("REVOKE INSERT ON obs.job_run FROM PUBLIC");

            db.superuserExecute("ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA obs GRANT SELECT ON TABLES TO PUBLIC");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("default privileges");
            db.superuserExecute("ALTER DEFAULT PRIVILEGES FOR ROLE antlab_owner IN SCHEMA obs REVOKE SELECT ON TABLES FROM PUBLIC");

            db.superuserExecute("CREATE TABLE obs.not_a_view (a int)");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("obs.not_a_view is not a view");
            db.superuserExecute("DROP TABLE obs.not_a_view");

            db.superuserExecute("CREATE TABLE public.reader_owned (a int)");
            db.superuserExecute("ALTER TABLE public.reader_owned OWNER TO grafana_reader");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("owns 1 object");
            db.superuserExecute("ALTER TABLE public.reader_owned OWNER TO antlab_owner");
            db.superuserExecute("DROP TABLE public.reader_owned");

            assertThat(grants(db.superuser())).isEqualTo(before);
            SERVER.runGrafanaScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(before);
        }
    }

    @Test
    void scriptDoesNotCreateARoleWhenItRefuses() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs10")) {
            db.createRoles();
            db.superuserExecute("DROP VIEW obs.agent_turn");
            db.superuserExecute("CREATE TABLE obs.not_a_view (a int)");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("not a view");
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'grafana_reader'", Integer.class)).isZero();
        }
    }

    @Test
    void scriptRefusesWhenTheViewsAreMissing() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs11")) {
            SERVER.migrate(db.name(), SERVER.container().getUsername(), false);
            db.superuserExecute("DROP SCHEMA obs CASCADE");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("schema obs does not exist");
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'grafana_reader'", Integer.class)).isZero();
        }
    }

    @Test
    void aViewAddedLaterNeedsTheScriptAgainAndTheScriptGrantsIt() throws SQLException {
        try (RoleTestServer.Db db = database("obs12", false)) {
            db.superuserExecute("CREATE VIEW obs.later AS SELECT 1 AS one");
            db.superuserExecute("ALTER VIEW obs.later OWNER TO antlab_owner");
            try (Connection c = reader(db)) {
                assertThat(sqlState(c, "SELECT * FROM obs.later")).isEqualTo(DENIED);
            }
            SERVER.runGrafanaScript(db.name());
            try (Connection c = reader(db)) {
                assertThat(sqlState(c, "SELECT * FROM obs.later")).isEqualTo("no error");
            }
        }
    }

    @Test
    void ownerScriptCreatesObsAndMovesViewsMigratedBySuperuser() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs13")) {
            SERVER.migrate(db.name(), SERVER.container().getUsername(), false);
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForList("SELECT DISTINCT relowner::regrole::text FROM pg_class WHERE relnamespace = 'obs'::regnamespace",
                    String.class)).containsExactly(SERVER.container().getUsername());

            SERVER.runRoleScript(db.name());
            SERVER.setPasswords(db.name());
            SERVER.migrate(db.name(), "antlab_owner", true);

            assertThat(jdbc.queryForList("SELECT DISTINCT relowner::regrole::text FROM pg_class WHERE relnamespace = 'obs'::regnamespace",
                    String.class)).containsExactly("antlab_owner");
            assertThat(jdbc.queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'obs'", String.class))
                    .isEqualTo("antlab_owner");
            SERVER.runRoleScript(db.name());
            assertThat(jdbc.queryForObject("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'obs'", String.class))
                    .isEqualTo("antlab_owner");
        }
    }

    @Test
    void upgradeFromV12ThroughTheOwnerScriptToV13() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs17")) {
            SERVER.runRoleScript(db.name());
            SERVER.setPasswords(db.name());
            db.superuserExecute("DROP SCHEMA obs");
            org.flywaydb.core.Flyway v12 = org.flywaydb.core.Flyway.configure()
                    .configuration(SERVER.flyway(db.name(), "antlab_owner", true).getConfiguration()).target("12").load();
            v12.migrate();
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForObject("SELECT to_regnamespace('obs') IS NULL", Boolean.class)).isTrue();

            SERVER.runRoleScript(db.name());
            SERVER.migrate(db.name(), "antlab_owner", true);
            SERVER.runGrafanaScript(db.name());

            assertThat(viewNames(jdbc)).containsExactlyInAnyOrderElementsOf(COLUMNS.keySet());
            assertThat(jdbc.queryForList("SELECT DISTINCT relowner::regrole::text FROM pg_class WHERE relnamespace = 'obs'::regnamespace",
                    String.class)).containsExactly("antlab_owner");
        }
    }

    @Test
    void rejectAllowlistEqualsDecisionRejectCode() throws SQLException {
        try (RoleTestServer.Db db = database("obs18", false)) {
            String def = db.superuser().queryForObject("SELECT pg_get_viewdef('obs.agent_call_reject'::regclass)", String.class);
            Set<String> inView = new java.util.TreeSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(def);
            while (m.find()) {
                inView.add(m.group(1));
            }
            inView.remove("OTHER");
            Set<String> enumNames = new java.util.TreeSet<>();
            for (antlab.agent.decision.DecisionRejectCode code : antlab.agent.decision.DecisionRejectCode.values()) {
                enumNames.add(code.name());
            }
            assertThat(inView).as("the view allowlist must follow DecisionRejectCode").isEqualTo(enumNames);
        }
    }

    @Test
    void ownerAndReaderScriptsRefuseColumnGrantsAndSchemaUsageDriftWithAndWithoutTheReaderRole() throws SQLException {
        for (boolean readerExists : new boolean[] {true, false}) {
            try (RoleTestServer.Db db = readerExists ? database("obs19", false) : plainDatabase("obs19b")) {
                JdbcTemplate jdbc = db.superuser();
                String clean = aclSnapshot(jdbc);
                db.superuserExecute("GRANT USAGE ON SCHEMA obs TO PUBLIC");
                db.superuserExecute("GRANT SELECT (cost_usd) ON obs.agent_call TO PUBLIC");
                String drifted = aclSnapshot(jdbc);
                assertThat(drifted).isNotEqualTo(clean);
                assertThatThrownBy(() -> SERVER.runRoleScript(db.name())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("schema obs grants USAGE to PUBLIC")
                        .hasMessageContaining("column-level right on obs.agent_call.cost_usd");
                assertThat(aclSnapshot(jdbc)).as("owner script refused, nothing changed (reader exists: %s)", readerExists).isEqualTo(drifted);
                assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("schema obs grants USAGE to PUBLIC")
                        .hasMessageContaining("column-level right on obs.agent_call.cost_usd");
                assertThat(aclSnapshot(jdbc)).as("reader script refused, nothing changed (reader exists: %s)", readerExists).isEqualTo(drifted);

                db.superuserExecute("REVOKE USAGE ON SCHEMA obs FROM PUBLIC");
                db.superuserExecute("REVOKE SELECT (cost_usd) ON obs.agent_call FROM PUBLIC");
                db.superuserExecute("GRANT SELECT (cost_usd) ON obs.agent_call TO antlab_app");
                String appDrift = aclSnapshot(jdbc);
                assertThatThrownBy(() -> SERVER.runRoleScript(db.name())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("column-level right on obs.agent_call.cost_usd");
                assertThat(aclSnapshot(jdbc)).isEqualTo(appDrift);
                db.superuserExecute("REVOKE SELECT (cost_usd) ON obs.agent_call FROM antlab_app");
                SERVER.runRoleScript(db.name());
                SERVER.runGrafanaScript(db.name());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'grafana_reader'", Integer.class)).isOne();
            }
        }
    }

    @Test
    void turnBoundaryShowsTheCurrentTurnAndHidesTheNextOne() throws SQLException {
        // Rule pinned: a row of turn k is visible when k <= season.current_turn_no, so turn 0 is visible at current_turn_no 0
        // (orders and calls of turn 0 exist before the first advance) and turn k + 1 is hidden until the season reaches it.
        try (RoleTestServer.Db db = database("obs21", true)) {
            long ags2 = db.superuser().queryForObject("SELECT season_id FROM season_agent_config WHERE persona_key = 'other-agent'", Long.class);
            db.superuserExecute("""
                    INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text,
                            tokens_in, latency_ms)
                        VALUES (%1$d, 0, 1, 'TURN_DECISION', 'OK', 'vendor/model-b', 'p', 555, 5);
                    INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, request_text,
                            tokens_in, latency_ms, is_final, outcome, rejects, request_sha256)
                        VALUES (%1$d, 1, 2, 'TURN_DECISION', 'OK', 'vendor/model-b', 'p', 1, 5, true, 'DECISION',
                                '[{"unit":"ORDER","index":0,"code":"QUANTITY_INVALID","pointer":null}]'::jsonb, repeat('b', 64)),
                               (%1$d, 2, 1, 'TURN_DECISION', 'OK', 'vendor/model-b', 'p', 2, 5, true, 'DECISION',
                                '[{"unit":"ORDER","index":0,"code":"SCHEMA_INVALID","pointer":null},
                                  {"unit":"ORDER","index":1,"code":"SCHEMA_INVALID","pointer":null}]'::jsonb, repeat('b', 64));
                    INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
                        SELECT %1$d, asset_id, t.n, t.side, 1, 'PENDING', 'DIRECT'
                        FROM (SELECT asset_id FROM orders LIMIT 1) a, (VALUES (0, 'SELL'), (2, 'SELL')) AS t(n, side);
                    INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value,
                            midfielder_value, forward_value, drawdown)
                        VALUES (%1$d, 2, 4444444, 0, 4444444, 4444444, 0, 0, 0, -0.04);
                    """.formatted(ags2));
            JdbcTemplate reader = SERVER.jdbc(db.name(), "grafana_reader", RoleTestServer.PASSWORD);

            // current_turn_no = 0
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call WHERE tokens_in = 555", Integer.class)).isOne();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call WHERE tokens_in IN (777, 1, 2)", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call_reject", Integer.class)).isEqualTo(4);
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_order WHERE side = 'SELL' AND turn_no = 0", Integer.class)).isOne();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_order WHERE turn_no >= 1 AND side = 'BUY'", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_turn", Integer.class)).isOne();

            // current_turn_no = 1: turn 1 appears, turn 2 stays hidden
            db.superuserExecute("UPDATE season SET current_turn_no = 1 WHERE id = " + ags2);
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_turn WHERE total_value = 5555555", Integer.class)).isOne();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_turn WHERE total_value = 4444444", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_order WHERE side = 'BUY' AND turn_no = 1", Integer.class)).isOne();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_order WHERE turn_no = 2", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call WHERE tokens_in = 777", Integer.class)).isOne();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call WHERE tokens_in = 2", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT count(*) FROM obs.agent_call_reject", Integer.class)).as("turn 1 reject shows, turn 2 rejects stay hidden").isEqualTo(5);
        }
    }

    @Test
    void readerScriptRefusesWhenTheSchemaExistsButTheViewsDoNot() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs20")) {
            SERVER.migrate(db.name(), SERVER.container().getUsername(), false);
            db.superuserExecute("DROP SCHEMA obs CASCADE");
            db.superuserExecute("CREATE SCHEMA obs");
            assertThatThrownBy(() -> SERVER.runGrafanaScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("baseline view obs.job_run is missing");
            assertThat(db.superuser().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'grafana_reader'", Integer.class)).isZero();
        }
    }

    @Test
    void ownerScriptRefusesAnObsViewThatAppOrPublicCanRead() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs14")) {
            db.createRoles();
            db.superuserExecute("GRANT SELECT ON obs.job_run TO antlab_app");
            assertThatThrownBy(() -> SERVER.runRoleScript(db.name())).isInstanceOf(SQLException.class)
                    .hasMessageContaining("obs.job_run");
        }
    }

    @Test
    void flywayAsTheOwnerExplainsAMissingSchema() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "obs15")) {
            SERVER.runRoleScript(db.name());
            SERVER.setPasswords(db.name());
            db.superuserExecute("DROP SCHEMA obs");
            assertThatThrownBy(() -> SERVER.migrate(db.name(), "antlab_owner", true)).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("db/roles/antlab_owner_app.sql");
        }
    }

    @Test
    void engineAndReaderScriptsDoNotDisturbEachOther() throws SQLException {
        try (RoleTestServer.Db db = database("obs16", false)) {
            String reader = grants(db.superuser());
            SERVER.runEngineScript(db.name());
            assertThat(grants(db.superuser())).isEqualTo(reader);
            JdbcTemplate jdbc = db.superuser();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c WHERE c.relnamespace = 'obs'::regnamespace "
                    + "AND has_table_privilege('antlab_engine', c.oid, 'SELECT')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT has_schema_privilege('antlab_engine', 'obs', 'USAGE')", Boolean.class)).isFalse();
            SERVER.runGrafanaScript(db.name());
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('antlab_engine', 'public.asset', 'SELECT')", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('grafana_reader', 'public.asset', 'SELECT')", Boolean.class)).isFalse();
            List<String> unexpected = new ArrayList<>(jdbc.queryForList("SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace "
                    + "AND relkind = 'r' AND has_table_privilege('grafana_reader', oid, 'SELECT') ORDER BY 1", String.class));
            assertThat(unexpected).containsExactly("series", "series_observation");
        }
    }
}
