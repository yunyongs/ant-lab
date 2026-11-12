package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.support.GoldenCleanup;
import antlab.support.GoldenFixtureSource;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 기준일 판정 대조 — 표본을 적재하고 12 정의로 계산한 {@code asset_position} 저장값(참여율 2·변동성·최대 낙폭·시스템 포지션)이
 * Java {@link PositionMeter}(기준일 최소 24·5·5)로 같은 기준일 창의 배열을 다시 잰 값과 풀 전 종목에서 같다.
 * 10턴 시즌은 시즌 실측이 전부 판정 불능이라 26 골든만으로는 판정 양성 사례가 비므로, 이 시험이 판정 로직을 실데이터로 묶는다.
 * 입력 배열은 12 의 {@code pos_series}(창 안에서 종목·시장 수익률이 둘 다 있는 달)를 달 순서로 읽은 것이다. 표본 CSV 가 없으면 건너뛴다.
 */
@Tag("sql-golden")
class PositionMeterBaseDateGoldenTest {

    private static final String DEFINITION = "db/12_calc_position_def.sql";

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static GoldenFixtureSource.FixtureAvailability availability;

    @BeforeAll
    static void start() {
        GoldenFixtureSource source = GoldenFixtureSource.fromEnvironment();
        availability = source.inspect();
        if (availability.explicit()) {
            availability.requireOrSkip();
        }
        if (!availability.isComplete()) {
            System.out.println("[sql-golden] 건너뜀 — " + availability.skipMessage());
            return;
        }
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, source);
    }

    @AfterAll
    static void stop() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }

    private static RowColumn col(String name, ColumnType type) {
        return new RowColumn(name, type);
    }

    private record Stored(Long months, BigDecimal up, BigDecimal down, BigDecimal volatility, BigDecimal maxDrawdown,
            String position) {
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void 기준일_풀_전_종목의_판정이_저장값과_같다() throws Exception {
        availability.requireOrSkip();
        harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("position_base_date");
        harness.execute(db, SqlGoldenScenario.positionBaseDate());

        RowSet series = harness.queryWithDefinitions(db, DEFINITION, new RowQuery("series", """
                SELECT sid AS sid, kind AS kind, base_date AS base_date, r_krw AS r_krw, r_mkt AS r_mkt
                FROM pos_series ORDER BY sid, month_start""",
                List.of(col("sid", ColumnType.TEXT), col("kind", ColumnType.TEXT), col("base_date", ColumnType.DATE),
                        col("r_krw", ColumnType.DECIMAL), col("r_mkt", ColumnType.DECIMAL))), 0L);
        RowSet stored = harness.queryWithDefinitions(db, DEFINITION, new RowQuery("stored", """
                SELECT ap.id::bigint AS id, ap.base_date AS base_date, ap.months_used::bigint AS months_used,
                       ap.up_participation AS up_participation, ap.down_participation AS down_participation,
                       ap.volatility AS volatility, ap.max_drawdown AS max_drawdown, ap.system_position AS system_position
                FROM asset_position ap ORDER BY ap.base_date, ap.id""",
                List.of(col("id", ColumnType.LONG), col("base_date", ColumnType.DATE), col("months_used", ColumnType.LONG),
                        col("up_participation", ColumnType.DECIMAL), col("down_participation", ColumnType.DECIMAL),
                        col("volatility", ColumnType.DECIMAL), col("max_drawdown", ColumnType.DECIMAL),
                        col("system_position", ColumnType.TEXT))), 0L);

        // 기준일마다: 시장 시리즈 하나, 종목 시리즈 여럿(sid = A:<기준일 종목번호>)
        Map<String, List<BigDecimal>> assetReturns = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> assetMarket = new LinkedHashMap<>();
        Map<LocalDate, List<BigDecimal>> market = new TreeMap<>();
        for (int i = 0; i < series.size(); i++) {
            String sid = (String) series.get(i, "sid");
            LocalDate baseDate = (LocalDate) series.get(i, "base_date");
            if ("M".equals(series.get(i, "kind"))) {
                market.computeIfAbsent(baseDate, d -> new ArrayList<>()).add((BigDecimal) series.get(i, "r_mkt"));
            } else {
                assetReturns.computeIfAbsent(sid, k -> new ArrayList<>()).add((BigDecimal) series.get(i, "r_krw"));
                assetMarket.computeIfAbsent(sid, k -> new ArrayList<>()).add((BigDecimal) series.get(i, "r_mkt"));
            }
        }

        Map<LocalDate, Map<String, Stored>> storedByDate = new TreeMap<>();
        for (int i = 0; i < stored.size(); i++) {
            storedByDate.computeIfAbsent((LocalDate) stored.get(i, "base_date"), d -> new LinkedHashMap<>())
                    .put("A:" + stored.get(i, "id"), new Stored((Long) stored.get(i, "months_used"),
                            (BigDecimal) stored.get(i, "up_participation"), (BigDecimal) stored.get(i, "down_participation"),
                            (BigDecimal) stored.get(i, "volatility"), (BigDecimal) stored.get(i, "max_drawdown"),
                            (String) stored.get(i, "system_position")));
        }
        assertThat(storedByDate).isNotEmpty();

        List<String> mismatches = new ArrayList<>();
        int judged = 0;
        int unjudged = 0;
        Map<String, Integer> positions = new TreeMap<>();
        for (Map.Entry<LocalDate, Map<String, Stored>> day : storedByDate.entrySet()) {
            Map<String, PositionMeter.Metrics> metrics = new LinkedHashMap<>();
            for (String sid : day.getValue().keySet()) {
                metrics.put(sid, PositionMeter.measure(assetReturns.getOrDefault(sid, List.of()),
                        assetMarket.getOrDefault(sid, List.of()), ReportRules.BASE_MIN_MONTHS, ReportRules.BASE_MIN_UP,
                        ReportRules.BASE_MIN_DOWN));
            }
            Map<String, Position> measured = PositionMeter.classifyPool(metrics,
                    PositionMeter.maxDrawdown(market.get(day.getKey())));
            for (Map.Entry<String, Stored> entry : day.getValue().entrySet()) {
                Stored want = entry.getValue();
                PositionMeter.Metrics got = metrics.get(entry.getKey());
                Position position = measured.get(entry.getKey());
                if (want.position() == null) {
                    unjudged++;
                    if (got.judgeable() || position != null) {
                        mismatches.add("판정 불능이어야 하는데 Java 가 판정했다: " + entry.getKey());
                    }
                    continue;
                }
                judged++;
                positions.merge(want.position(), 1, Integer::sum);
                boolean same = got.judgeable() && position != null
                        && want.months() == got.monthsUsed()
                        && want.up().compareTo(got.up()) == 0 && want.down().compareTo(got.down()) == 0
                        && want.volatility().compareTo(got.volatility()) == 0
                        && want.maxDrawdown().compareTo(got.maxDrawdown()) == 0
                        && want.position().equals(position.name());
                if (!same) {
                    mismatches.add("저장값과 갈린 종목: " + entry.getKey());
                }
            }
        }
        System.out.println("[sql-golden] 기준일 판정 대조 — 판정 " + judged + ", 판정 불능 " + unjudged + ", 포지션별 " + positions);
        assertThat(mismatches).as("저장값과 갈린 종목").isEmpty();
        assertThat(judged).as("판정 양성 사례").isGreaterThan(0);
    }
}
