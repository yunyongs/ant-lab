package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.dbrole.RoleTestServer;
import antlab.support.GrafanaDashboards;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * OBS-03: every SQL target of the collectors and overview dashboards runs as {@code grafana_reader} on a real PostgreSQL 17
 * (own container, see {@link RoleTestServer}), with the Grafana macros replaced by plain SQL. Proves the panels execute,
 * that the two local {@code job_run} rows show up, and that a job without a row shows "no run" instead of an error.
 */
class ObsDashboardsQueryTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();

    private static final String SEED = """
            DO $seed$
            DECLARE
                g bigint; ast bigint; jr bigint; jr2 bigint; ser bigint;
            BEGIN
                INSERT INTO asset_group (code, name, kind) VALUES ('G1', 'group', 'SECTOR') RETURNING id INTO g;
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                    VALUES ('KR', 'T1', 'Asset Name', 'STOCK', 'KRW', g, '2000-01-01', 'PRICE') RETURNING id INTO ast;
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count,
                        revised_count, unchanged_count, failed_target_count, error_code, error_detail)
                    VALUES ('FRED_SERIES', 'MANUAL', 'PARTIAL', now() - interval '3 days', now() - interval '3 days' + interval '5 seconds',
                        12, 30, 1, 5, 1, 'HTTP_429', 'SECRET-ERROR-DETAIL') RETURNING id INTO jr;
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count,
                        revised_count, unchanged_count, failed_target_count)
                    VALUES ('ECOS_SERIES', 'SCHEDULED', 'SUCCEEDED', now() - interval '1 day', now() - interval '1 day' + interval '60 seconds',
                        3, 3, 0, 0, 0) RETURNING id INTO jr2;
                INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                    VALUES ('CPI', 'FRED', 'CPIAUCSL', 'INDEX', 'MONTHLY', 10, true) RETURNING id INTO ser;
                INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                    VALUES ('EMPTY_SERIES', 'ECOS', 'X1', 'INDEX', 'DAILY', 1, false);
                INSERT INTO series_observation (series_id, ref_date, value, observed_at, vintage_no, job_run_id)
                    VALUES (ser, '2020-01-01', 1.5, now() - interval '3 days', 1, jr);
                INSERT INTO asset_daily_price (asset_id, trade_date, price_basis, source, close_price, vintage_no, observed_at, job_run_id)
                    VALUES (ast, current_date - 2, 'RAW', 'KIS', 70000, 1, now(), jr2);
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

    /** The two macros the dashboards use, replaced the way Grafana's PostgreSQL macros expand them (a 60-day window). */
    static String expandMacros(String sql) {
        String window = "BETWEEN now() - interval '60 days' AND now()";
        return sql.replaceAll("\\$__timeFilter\\(([^)]*)\\)", "$1 " + window)
                .replaceAll("\\$__timeGroupAlias\\(([^,]*),\\s*'1d'\\)", "floor(extract(epoch FROM $1) / 86400) * 86400 AS \"time\"");
    }

    private record Result(List<String> columns, List<List<String>> rows) {}

    private static Result run(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(expandMacros(sql))) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<String>> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
            return new Result(columns, rows);
        }
    }

    private static JsonNode target(JsonNode dashboard, int panelId, String refId) {
        for (JsonNode panel : GrafanaDashboards.panels(dashboard)) {
            if (panel.path("id").asInt() == panelId) {
                for (JsonNode t : panel.path("targets")) {
                    if (refId.equals(t.path("refId").asString())) {
                        return t;
                    }
                }
            }
        }
        throw new AssertionError("no target " + panelId + "/" + refId);
    }

    private static String sql(JsonNode dashboard, int panelId) {
        return target(dashboard, panelId, "A").path("rawSql").asString();
    }

    @Test
    void 모든_SQL_표적은_읽기_역할로_실행되고_시드된_실행_두_건이_보인다() throws SQLException, IOException {
        var dashboards = GrafanaDashboards.load();
        JsonNode collectors = dashboards.get("collectors.json");
        JsonNode overview = dashboards.get("overview.json");
        try (RoleTestServer.Db db = database("obs3a", true);
                Connection c = SERVER.connect(db.name(), "grafana_reader", RoleTestServer.PASSWORD)) {
            for (JsonNode d : List.of(collectors, overview)) {
                for (String statement : GrafanaDashboards.sqlStatements(d)) {
                    assertThat(run(c, statement).columns()).as(statement).isNotEmpty();
                }
            }
            Result last = run(c, sql(collectors, 1));
            assertThat(last.rows()).hasSize(3);
            assertThat(last.columns()).containsExactly("job", "status", "started", "age", "last success age", "requests", "failed targets", "error code");
            assertThat(last.rows().stream().map(r -> r.get(0) + ":" + r.get(1)).toList())
                    .containsExactly("ECOS_SERIES:SUCCEEDED", "FRED_SERIES:PARTIAL", "KIS_DAILY_PRICE:no run");
            assertThat(last.rows().get(1).get(7)).isEqualTo("HTTP_429");
            assertThat(last.rows().get(2).subList(2, 8)).containsOnlyNulls();
            assertThat(Double.parseDouble(last.rows().get(0).get(3))).isBetween(86_000.0, 90_000.0);
            assertThat(run(c, sql(overview, 6)).rows()).hasSize(3);

            assertThat(run(c, sql(collectors, 2)).rows()).hasSize(2);
            Result requests = run(c, sql(collectors, 3));
            assertThat(requests.rows().stream().map(r -> r.get(1)).toList()).containsExactlyInAnyOrder("FRED_SERIES", "ECOS_SERIES");
            Result caps = run(c, target(collectors, 3, "B").path("rawSql").asString());
            assertThat(caps.rows().stream().map(r -> r.get(1) + "=" + r.get(2)).toList())
                    .containsExactlyInAnyOrder("FRED_SERIES cap=30", "ECOS_SERIES cap=20");
            assertThat(run(c, sql(collectors, 4)).rows()).hasSize(6);
            Result failed = run(c, sql(collectors, 5));
            assertThat(failed.rows()).hasSize(1);
            assertThat(failed.rows().get(0)).contains("HTTP_429", "PARTIAL");

            Result series = run(c, sql(collectors, 6));
            assertThat(series.rows()).hasSize(2);
            assertThat(series.rows().get(0).get(1)).isEqualTo("EMPTY_SERIES");
            assertThat(series.rows().get(0).get(3)).isNull();
            assertThat(series.rows().get(1).get(3)).isEqualTo("2020-01-01");
            Result prices = run(c, sql(collectors, 7));
            assertThat(prices.rows()).hasSize(1);
            assertThat(prices.rows().get(0).get(0)).isEqualTo("KR");
            assertThat(prices.rows().get(0).get(3)).isEqualTo("2");

            assertThat(Long.parseLong(run(c, sql(overview, 5)).rows().get(0).get(0))).isPositive();
            assertThat(run(c, sql(overview, 8)).rows().get(0).get(0)).isEqualTo("2");
            assertThat(Integer.parseInt(run(c, sql(overview, 7)).rows().get(0).get(0))).isGreaterThan(2000);
        }
    }

    @Test
    void 데이터가_없으면_오류_없이_no_run과_빈_결과가_나온다() throws SQLException, IOException {
        var dashboards = GrafanaDashboards.load();
        JsonNode collectors = dashboards.get("collectors.json");
        JsonNode overview = dashboards.get("overview.json");
        try (RoleTestServer.Db db = database("obs3b", false);
                Connection c = SERVER.connect(db.name(), "grafana_reader", RoleTestServer.PASSWORD)) {
            for (JsonNode d : List.of(collectors, overview)) {
                for (String statement : GrafanaDashboards.sqlStatements(d)) {
                    assertThat(run(c, statement).columns()).as(statement).isNotEmpty();
                }
            }
            Result last = run(c, sql(collectors, 1));
            assertThat(last.rows().stream().map(r -> r.get(1)).toList()).containsExactly("no run", "no run", "no run");
            Result overviewLast = run(c, sql(overview, 6));
            assertThat(overviewLast.rows().stream().map(r -> r.get(1)).toList()).containsExactly("no run", "no run", "no run");
            assertThat(run(c, sql(collectors, 2)).rows()).isEmpty();
            assertThat(run(c, sql(collectors, 5)).rows()).isEmpty();
            assertThat(run(c, sql(collectors, 6)).rows()).isEmpty();
            assertThat(run(c, sql(collectors, 7)).rows()).isEmpty();
            assertThat(run(c, sql(overview, 7)).rows().get(0).get(0)).isNull();
            assertThat(run(c, sql(overview, 8)).rows().get(0).get(0)).isNull();
        }
    }
}
