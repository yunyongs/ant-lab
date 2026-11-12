package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.support.GoldenCleanup;
import antlab.support.PsqlScriptRunner;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario.ScriptStep;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 상수 대조 — SQL 정의(12 의 {@code pos_k}·{@code pos_classify}, 26 의 {@code sr_k})와 Java {@link ReportRules}·{@link PositionMeter}가 같다.
 * 표본 CSV 가 필요 없다: 스키마(01–03)와 정의 파일만 일회용 PostgreSQL 17 에 놓고, 26 을 {@code -f} 로 읽은 같은 세션에서 질의한다
 * (26 이 12 → 21 → 25 정의를 {@code \ir} 로 읽는다). 정의를 바꾸면 Java 도 같은 PR 에서 바꾸라는 규칙을 이 시험이 강제한다.
 */
class ReportRulesSqlParityTest {

    private static final String DEFINITION = "db/26_season_report_def.sql";

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static DatabaseHandle db;

    @BeforeAll
    static void start() throws Exception {
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, antlab.support.GoldenFixtureSource.fromEnvironment());
        PsqlScriptRunner runner = harness.runner();
        try (Stream<Path> files = Files.list(Path.of("db"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("(0[123]_schema_.*|12_.*_def|21_.*_def|25_.*_def|26_.*_def)\\.sql"))
                    .filter(p -> !p.getFileName().toString().endsWith("_check.sql")).toList()) {
                runner.putFile("db/" + file.getFileName(), file);
            }
        }
        db = harness.createSqlDatabase("rules_parity");
        for (String schema : List.of("db/01_schema_history.sql", "db/02_schema_season.sql", "db/03_schema_account.sql")) {
            SqlGoldenHarness.ScriptResult result = harness.run(db,
                    new ScriptStep(schema, antlab.support.SqlGoldenScenario.Kind.RUN, null, Duration.ofMinutes(2)));
            assertThat(result.process().exitCode()).as(schema).isZero();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    private static String literals(String[] values) {
        return Arrays.stream(values).map(v -> "(" + v + "::numeric)").collect(java.util.stream.Collectors.joining(", "));
    }

    private static RowColumn col(String name, ColumnType type) {
        return new RowColumn(name, type);
    }

    private static RowSet query(String name, String sql, RowColumn... columns) throws Exception {
        return harness.queryWithDefinitions(db, DEFINITION, new RowQuery(name, sql, List.of(columns)), 0L);
    }

    /** M1: 26 정의가 스키마만 있는 DB 에서 읽히고 상수 행이 1행씩 나온다. */
    @Test
    void pos_k_상수가_ReportRules와_같다() throws Exception {
        RowSet rows = query("pos_k", """
                SELECT win_months::bigint AS win_months, end_lag::bigint AS end_lag, min_months::bigint AS min_months,
                       min_up::bigint AS min_up, min_down::bigint AS min_down, band AS band, top_ratio AS top_ratio,
                       kr_market AS kr_market, kr_ticker AS kr_ticker, us_market AS us_market, us_ticker AS us_ticker,
                       w_kr AS w_kr, w_us AS w_us
                FROM pos_k""",
                col("win_months", ColumnType.LONG), col("end_lag", ColumnType.LONG), col("min_months", ColumnType.LONG),
                col("min_up", ColumnType.LONG), col("min_down", ColumnType.LONG), col("band", ColumnType.DECIMAL),
                col("top_ratio", ColumnType.DECIMAL), col("kr_market", ColumnType.TEXT), col("kr_ticker", ColumnType.TEXT),
                col("us_market", ColumnType.TEXT), col("us_ticker", ColumnType.TEXT), col("w_kr", ColumnType.DECIMAL),
                col("w_us", ColumnType.DECIMAL));
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0, "win_months")).isEqualTo((long) ReportRules.WINDOW_MONTHS);
        assertThat(rows.get(0, "end_lag")).isEqualTo((long) ReportRules.WINDOW_END_LAG);
        assertThat(rows.get(0, "min_months")).isEqualTo((long) ReportRules.BASE_MIN_MONTHS);
        assertThat(rows.get(0, "min_up")).isEqualTo((long) ReportRules.BASE_MIN_UP);
        assertThat(rows.get(0, "min_down")).isEqualTo((long) ReportRules.BASE_MIN_DOWN);
        assertThat((BigDecimal) rows.get(0, "band")).isEqualByComparingTo(ReportRules.BAND);
        assertThat((BigDecimal) rows.get(0, "top_ratio")).isEqualByComparingTo(ReportRules.TOP_RATIO);
        assertThat(rows.get(0, "kr_market")).isEqualTo(ReportRules.BENCH_KR_MARKET);
        assertThat(rows.get(0, "kr_ticker")).isEqualTo(ReportRules.BENCH_KR_TICKER);
        assertThat(rows.get(0, "us_market")).isEqualTo(ReportRules.BENCH_US_MARKET);
        assertThat(rows.get(0, "us_ticker")).isEqualTo(ReportRules.BENCH_US_TICKER);
        assertThat((BigDecimal) rows.get(0, "w_kr")).isEqualByComparingTo(ReportRules.BENCH_KR_WEIGHT);
        assertThat((BigDecimal) rows.get(0, "w_us")).isEqualByComparingTo(ReportRules.BENCH_US_WEIGHT);
    }

    @Test
    void sr_k_상수가_ReportRules와_같다() throws Exception {
        RowSet rows = query("sr_k", """
                SELECT min_months::bigint AS min_months, min_up::bigint AS min_up, min_down::bigint AS min_down,
                       annual::bigint AS annual, array_to_string(nudge_sources, ',') AS nudge_sources,
                       array_to_string(platform_sources, ',') AS platform_sources
                FROM pg_temp.sr_k()""",
                col("min_months", ColumnType.LONG), col("min_up", ColumnType.LONG), col("min_down", ColumnType.LONG),
                col("annual", ColumnType.LONG), col("nudge_sources", ColumnType.TEXT), col("platform_sources", ColumnType.TEXT));
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0, "min_months")).isEqualTo((long) ReportRules.SEASON_MIN_MONTHS);
        assertThat(rows.get(0, "min_up")).isEqualTo((long) ReportRules.SEASON_MIN_UP);
        assertThat(rows.get(0, "min_down")).isEqualTo((long) ReportRules.SEASON_MIN_DOWN);
        assertThat(rows.get(0, "annual")).isEqualTo((long) ReportRules.ANNUAL_TURNS);
        assertThat(new HashSet<>(Arrays.asList(((String) rows.get(0, "nudge_sources")).split(","))))
                .isEqualTo(ReportRules.NUDGE_SOURCES);
        assertThat(new HashSet<>(Arrays.asList(((String) rows.get(0, "platform_sources")).split(","))))
                .isEqualTo(ReportRules.PLATFORM_SOURCES);
    }

    /** 문턱 위·아래·정확히 문턱인 값을 격자로 훑어 모든 가지에서 Java 판정 = SQL {@code pos_classify}. */
    @Test
    void 판정_문턱이_SQL_pos_classify와_모든_가지에서_같다() throws Exception {
        String[] ups = {"0.5", "0.99999999", "1.00000000", "1.09999999", "1.10000000", "1.10000001", "1.29999999",
                "1.30000000", "1.30000001", "2.0"};
        String[] downs = {"-0.1", "0.19999999", "0.20000000", "0.20000001", "0.59999999", "0.60000000", "0.79999999",
                "0.80000000", "1.19999999", "1.20000000", "1.20000001", "2.0"};
        // 시장 최대 낙폭 −0.2 → 1.5 배 = −0.3 이 경계(이하 포함)
        String marketMdd = "-0.20000000";
        String[] mdds = {"-0.1", "-0.29999999", "-0.30000000", "-0.30000001", "-0.9"};
        String marketMddSql = marketMdd + "::numeric";
        RowSet rows = query("classify", """
                SELECT u.up AS up, d.dn AS dn, m.mdd AS mdd, f.top AS top, f.bottom AS bottom,
                       pg_temp.pos_classify(u.up, d.dn, m.mdd, %s, f.top, f.bottom) AS pos
                FROM (VALUES %s) AS u(up)
                CROSS JOIN (VALUES %s) AS d(dn)
                CROSS JOIN (VALUES %s) AS m(mdd)
                CROSS JOIN (VALUES (false, false), (true, false), (false, true), (true, true)) AS f(top, bottom)
                ORDER BY u.up, d.dn, m.mdd, f.top, f.bottom""".formatted(marketMddSql, literals(ups), literals(downs), literals(mdds)),
                col("up", ColumnType.DECIMAL), col("dn", ColumnType.DECIMAL), col("mdd", ColumnType.DECIMAL),
                col("top", ColumnType.BOOLEAN), col("bottom", ColumnType.BOOLEAN), col("pos", ColumnType.TEXT));
        assertThat(rows.size()).isEqualTo(ups.length * downs.length * mdds.length * 4);
        List<String> mismatches = new ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Position java = PositionMeter.classify((BigDecimal) rows.get(i, "up"), (BigDecimal) rows.get(i, "dn"),
                    (BigDecimal) rows.get(i, "mdd"), new BigDecimal(marketMdd), (Boolean) rows.get(i, "top"),
                    (Boolean) rows.get(i, "bottom"));
            String sql = (String) rows.get(i, "pos");
            seen.add(sql);
            if (!java.name().equals(sql)) {
                mismatches.add("사례 " + i + " Java=" + java + " SQL=" + sql);
            }
        }
        assertThat(mismatches).as("SQL 과 갈린 판정").isEmpty();
        // 여섯 포지션이 모두 나와야 격자가 모든 가지를 지난 것이다
        assertThat(seen).hasSize(6);
    }
}
