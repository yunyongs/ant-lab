package antlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.history.MonthBlockRepository;
import antlab.season.FormationCost;
import antlab.season.FormationCostQuery;
import antlab.season.LineupChangeService;
import antlab.season.SeedGenerator;
import antlab.support.ComparisonSpec;
import antlab.support.DeferredPathSource;
import antlab.support.GoldenRowQueries;
import antlab.support.GoldenRowQueries.GoldenTable;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * 같은 턴 여러 변경 드릴 재생 골든 G4(P0-38 PR 3, P0-37 F1) — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>SQL 기준은 {@link SqlGoldenScenario#formationDrill()}: 턴 골든 5턴 지점 → 매매·전술 변경 드릴 {@code rebuy}(판정 {@code p0_19_drill_rebuy})
 * → 나머지 5턴. 드릴은 한 턴(5턴)에 주문과 전술 변경 셋을 섞는다: 주문 ① → 변경 1 → 주문 ②③(길이 없는 구간을 만드는 전량 매도·재매수)
 * → 변경 2 → 주문 ④⑤ → 주문 없는 변경 3. API 시즌은 캡처한 행동을 기록 시각 순으로 그대로 재생한다(RANKING·BENCH 출발점은 DIRECT 로 재생).
 * 끝난 두 시즌에서 본다: 변경 이력 논리 키(5/1·5/2·5/3), 주문별 변경 연결, 변경 비용 세 줄(주문 없는 변경 0원 한 줄),
 * 라인업 구간(길이 없는 구간 포함), 그 뒤 성적표(SQL = Java = 응답, 출발점 세 열은 반대쪽 값 단언).
 * 값(금액·수량·비중)은 출력하지 않는다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class FormationChangeDrillGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.startWithDrill(registry, "change_drill_api");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoldenBeans {
        @Bean
        @Primary
        DeferredPathSource deferredPathSource() {
            return new DeferredPathSource();
        }

        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> TeamBuyGoldenSeason.GOLDEN_SEED;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    DeferredPathSource deferred;
    @Autowired
    MonthBlockRepository monthBlocks;
    @Autowired
    LineupChangeService lineupChanges;
    @Autowired
    FormationCostQuery costQuery;
    @Autowired
    JdbcTemplate jdbc;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        // SQL 계획이 시즌을 끝까지 돌려 놓으므로 캡처한 행동(전환 1..10 포함)을 재생하면 API 시즌도 끝난다
        TeamBuyGoldenSeason.Replayed r = golden.replayTrades(mvc, deferred, monthBlocks, SqlGoldenScenario.formationDrill(),
                "change_drill_sql", (season, turn, asset, position) -> lineupChanges.change(season, turn, asset, Position.valueOf(position)));
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, apiSeason)).isEqualTo("FINISHED");
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private RowSet sql(RowQuery query) throws Exception {
        return harness.readRows(sqlDb, query, sqlSeason);
    }

    private RowSet api(RowQuery query) throws Exception {
        return harness.readRows(apiDb, query, apiSeason);
    }

    private void assertSameRows(GoldenTable table) throws Exception {
        RowSet expected = sql(table.query());
        RowSet actual = api(table.query());
        assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
        assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, table.spec());
    }

    // ---- 전술 변경 이력 ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4a_같은_턴_변경_셋이_논리_키_5_1_5_2_5_3으로_SQL과_같고_사슬이_이어진다() throws Exception {
        assertSameRows(GoldenRowQueries.formationChange());
        assertSameRows(GoldenRowQueries.season());
        for (RowSet changes : List.of(sql(GoldenRowQueries.formationChange().query()), api(GoldenRowQueries.formationChange().query()))) {
            assertThat(changes.size()).as("드릴은 같은 턴에 변경을 셋 한다").isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(changes.get(i, "turn_no")).isEqualTo(5L);
                assertThat(changes.get(i, "nth")).isEqualTo((long) i + 1);
            }
            // 사슬: 변경 k 의 이전 = 변경 k-1 의 이후
            for (int i = 1; i < 3; i++) {
                for (String part : List.of("goalkeeper", "defender", "midfielder", "forward")) {
                    assertThat(changes.get(i, "before_" + part)).as("변경 " + (i + 1) + " 이전 " + part)
                            .isEqualTo(changes.get(i - 1, "after_" + part));
                }
            }
        }
        // 기록 순서: 번호 순 = 기록 시각 순(Java 의 "번호 최대 = 마지막 변경" 과 SQL 의 "기록 시각 최근" 이 같은 변경을 가리킨다)
        RowQuery order = new RowQuery("change_order", """
                SELECT count(*) AS n FROM formation_change a JOIN formation_change b
                  ON b.season_id = a.season_id AND b.turn_no = a.turn_no AND b.id > a.id AND b.created_at <= a.created_at
                WHERE a.season_id = :sid""", List.of(new RowColumn("n", ColumnType.LONG)));
        assertThat(sql(order).get(0, "n")).as("SQL 번호 순과 시각 순이 어긋난 쌍").isEqualTo(0L);
        assertThat(api(order).get(0, "n")).as("API 번호 순과 시각 순이 어긋난 쌍").isEqualTo(0L);
        // 현재 포메이션 = 마지막 변경의 이후 값
        RowQuery chain = new RowQuery("chain", """
                SELECT count(*) AS n FROM season s JOIN formation_change fc ON fc.season_id = s.id
                WHERE s.id = :sid AND fc.id = (SELECT max(id) FROM formation_change WHERE season_id = s.id)
                  AND (fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward)
                    = (s.current_goalkeeper, s.current_defender, s.current_midfielder, s.current_forward)""",
                List.of(new RowColumn("n", ColumnType.LONG)));
        assertThat(sql(chain).get(0, "n")).isEqualTo(1L);
        assertThat(api(chain).get(0, "n")).isEqualTo(1L);
    }

    // ---- 주문별 변경 연결 ----

    private static long countKey(RowSet orders, String key) {
        long n = 0;
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i, "turn_no").equals(5L) && java.util.Objects.equals(orders.get(i, "formation_change_key"), key)) {
                n++;
            }
        }
        return n;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4b_주문이_자기보다_앞선_마지막_변경에_묶이고_출발점_두_열은_반대쪽을_단언한다() throws Exception {
        ComparisonSpec spec = ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status", "formation_change_key")
                .exclude("source", "SQL 드릴은 RANKING·BENCH 출발점을 쓰고 Phase 0 API 는 DIRECT 만 쓴다(Q5-A) — 아래에서 반대쪽을 단언한다")
                .exclude("has_turn_feed", "MVP 피드 출발점 — Phase 0 API 에 없다(Q5-A)")
                .build();
        RowSet sqlOrders = sql(GoldenRowQueries.orders().query());
        RowSet apiOrders = api(GoldenRowQueries.orders().query());
        assertThat(sqlOrders.size()).isPositive();
        RowSetDiff.assertNoDifference(sqlOrders, apiOrders, spec);

        for (RowSet orders : List.of(sqlOrders, apiOrders)) {
            assertThat(countKey(orders, null)).as("변경 전 주문(5턴 ①)").isEqualTo(1L);
            assertThat(countKey(orders, "5/1")).as("변경 1 뒤 주문 ②③").isEqualTo(2L);
            assertThat(countKey(orders, "5/2")).as("변경 2 뒤 주문 ④⑤").isEqualTo(2L);
            assertThat(countKey(orders, "5/3")).as("주문 없는 변경 3").isZero();
        }
        // 제외한 열의 반대쪽: SQL 은 RANKING 1건·BENCH 1건(피드 참조 없음), Java 는 전부 DIRECT·참조 0건
        int ranking = 0;
        int bench = 0;
        for (int i = 0; i < sqlOrders.size(); i++) {
            ranking += "RANKING".equals(sqlOrders.get(i, "source")) ? 1 : 0;
            bench += "BENCH".equals(sqlOrders.get(i, "source")) ? 1 : 0;
        }
        assertThat(ranking).as("SQL RANKING 주문").isEqualTo(1);
        assertThat(bench).as("SQL BENCH 주문").isEqualTo(1);
        for (int i = 0; i < apiOrders.size(); i++) {
            assertThat(apiOrders.get(i, "source")).as("Java 출발점").isEqualTo("DIRECT");
            assertThat(apiOrders.get(i, "has_turn_feed")).as("Java 피드 참조").isEqualTo(false);
        }
    }

    // ---- 변경 비용 ----

    private static final RowQuery SQL_COSTS = new RowQuery("formation_costs", """
            SELECT c.turn_no, row_number() OVER (PARTITION BY c.turn_no ORDER BY c.formation_change_id) AS nth,
                   c.order_count, c.buy_notional, c.sell_notional, c.fee_sum, c.tax_sum
            FROM pg_temp.formation_costs(:sid) c ORDER BY c.formation_change_id""", List.of(
            new RowColumn("turn_no", ColumnType.LONG), new RowColumn("nth", ColumnType.LONG),
            new RowColumn("order_count", ColumnType.LONG), new RowColumn("buy_notional", ColumnType.DECIMAL),
            new RowColumn("sell_notional", ColumnType.DECIMAL), new RowColumn("fee_sum", ColumnType.DECIMAL),
            new RowColumn("tax_sum", ColumnType.DECIMAL)));

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4c_변경_비용이_세_줄이고_SQL과_같으며_주문_없는_변경은_영원_한_줄이다() throws Exception {
        RowSet sqlCosts = harness.queryWithDefinitions(sqlDb, SqlGoldenScenario.TRADE_DEFINITIONS, SQL_COSTS, sqlSeason);
        List<FormationCost> java = costQuery.bySeason(apiSeason).stream()
                .sorted(Comparator.comparingLong(FormationCost::formationChangeId)).toList();
        assertThat(sqlCosts.size()).isEqualTo(3);
        assertThat(java).hasSize(3);
        long[] expectedOrders = {2, 2, 0};
        for (int i = 0; i < 3; i++) {
            FormationCost c = java.get(i);
            assertThat(c.turnNo()).as("변경 " + (i + 1) + " 턴").isEqualTo(((Long) sqlCosts.get(i, "turn_no")).intValue()).isEqualTo(5);
            assertThat(sqlCosts.get(i, "nth")).isEqualTo((long) i + 1);
            assertThat(c.orderCount()).as("변경 " + (i + 1) + " 주문 수").isEqualTo(expectedOrders[i]).isEqualTo(sqlCosts.get(i, "order_count"));
            assertThat(c.buyNotional()).isEqualByComparingTo((BigDecimal) sqlCosts.get(i, "buy_notional"));
            assertThat(c.sellNotional()).isEqualByComparingTo((BigDecimal) sqlCosts.get(i, "sell_notional"));
            assertThat(c.feeSum()).isEqualByComparingTo((BigDecimal) sqlCosts.get(i, "fee_sum"));
            assertThat(c.taxSum()).isEqualByComparingTo((BigDecimal) sqlCosts.get(i, "tax_sum"));
        }
        // 주문 없는 변경(셋째)은 모든 금액이 0인 한 줄이고, 앞의 둘은 비용이 있다(헛으로 일치하지 않게)
        FormationCost none = java.get(2);
        assertThat(none.buyNotional().signum()).isZero();
        assertThat(none.sellNotional().signum()).isZero();
        assertThat(none.feeSum().signum()).isZero();
        assertThat(none.taxSum().signum()).isZero();
        assertThat(java.get(0).feeSum().signum()).isPositive();
        assertThat(java.get(1).feeSum().signum()).isPositive();
        // 이중 계산 방지: 변경별 비용 합은 원장 FEE 합과 같다(원장은 독립 대조일 뿐 더하지 않는다)
        for (FormationCost c : java) {
            BigDecimal ledgerFee = jdbc.queryForObject("""
                    SELECT coalesce(sum(-l.amount), 0) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id
                    WHERE l.season_id = ? AND l.type = 'FEE' AND o.formation_change_id = ?""", BigDecimal.class, apiSeason,
                    c.formationChangeId());
            assertThat(ledgerFee).isEqualByComparingTo(c.feeSum());
        }
    }

    // ---- 라인업 구간 ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4d_라인업_구간이_같고_길이_없는_구간_둘이_양쪽에_있다() throws Exception {
        assertSameRows(GoldenRowQueries.lineup());
        RowQuery zero = new RowQuery("zero_length", """
                SELECT count(*) AS n FROM lineup lu WHERE lu.season_id = :sid AND lu.end_turn = lu.start_turn""",
                List.of(new RowColumn("n", ColumnType.LONG)));
        // 신규 매수로 연 구간이 같은 턴 전량 매도로, 재매수로 연 구간이 같은 턴 전량 매도로 닫혀 길이 없는 구간이 둘이다
        assertThat(sql(zero).get(0, "n")).as("SQL 길이 없는 구간").isEqualTo(2L);
        assertThat(api(zero).get(0, "n")).as("API 길이 없는 구간").isEqualTo(2L);
    }

    // ---- 나머지 표 ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4e_주문_체결_원장_보유_대사까지_열두_표가_SQL과_같다() throws Exception {
        RowSet sqlAssets = harness.readRows(sqlDb, GoldenRowQueries.assetKeys(), sqlSeason);
        RowSet apiAssets = harness.readRows(apiDb, GoldenRowQueries.assetKeys(), apiSeason);
        ComparisonSpec ordersSpec = ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status", "formation_change_key")
                .exclude("source", "G4b 가 반대쪽 값을 따로 단언한다")
                .exclude("has_turn_feed", "G4b 가 반대쪽 값을 따로 단언한다")
                .build();
        List<GoldenTable> tables = new ArrayList<>();
        for (GoldenTable t : GoldenRowQueries.allForDrillGolden()) {
            tables.add(t.name().equals("orders") ? new GoldenTable(t.query(), ordersSpec) : t);
        }
        tables.add(GoldenRowQueries.formationChange());
        tables.add(GoldenRowQueries.execution());
        tables.add(GoldenRowQueries.holding());
        for (GoldenTable table : tables) {
            RowSet expected = sql(table.query());
            RowSet actual = api(table.query());
            if (table.name().equals("reconciliation_log")) {
                expected = GoldenRowQueries.normalizeLogDetail(expected, sqlAssets);
                actual = GoldenRowQueries.normalizeLogDetail(actual, apiAssets);
            }
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                Long.class, apiSeason)).as("API 시즌의 불일치 대사 행").isZero();
    }

    // ---- 성적표 ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G4f_성적표가_SQL_Java_응답에서_같고_주문_없는_변경_하나와_변경_비용이_보인다() throws Exception {
        RowSet sqlReport = SeasonReportGoldenSupport.sqlReport(harness, sqlDb, sqlSeason);
        RowSet javaReport = SeasonReportGoldenSupport.sqlReport(harness, apiDb, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(sqlReport, javaReport, SeasonReportGoldenSupport.STARTING_POINT_COLUMNS))
                .as("SQL 시즌과 API 시즌에서 다른 열(출발점 세 열 제외)").isEmpty();
        JsonNode response = SeasonReportGoldenSupport.apiReport(mvc, apiSeason);
        assertThat(SeasonReportGoldenSupport.differences(javaReport, response)).as("응답과 정의 결과에서 다른 열").isEmpty();
        SeasonReportGoldenSupport.assertResponseFormat(response);

        // 같은 턴 여러 변경: 변경 셋, 주문이 묶인 건 4, 주문 없는 변경 1
        assertThat(sqlReport.get(0, "formation_changes")).isEqualTo(3L);
        assertThat(sqlReport.get(0, "formation_change_orders")).isEqualTo(4L);
        assertThat(sqlReport.get(0, "changes_without_orders")).isEqualTo(1L);
        assertThat(response.get("formationChanges").asLong()).isEqualTo(3L);
        assertThat(response.get("formationChangeOrders").asLong()).isEqualTo(4L);
        assertThat(response.get("changesWithoutOrders").asLong()).isEqualTo(1L);
        // 변경 비용 = 변경에 묶인 주문의 수수료 합 = 변경 비용 조회 세 줄의 합
        BigDecimal costFee = costQuery.bySeason(apiSeason).stream().map(FormationCost::feeSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(costFee.signum()).isPositive();
        assertThat((BigDecimal) sqlReport.get(0, "formation_change_fee")).isEqualByComparingTo(costFee);
        assertThat((BigDecimal) javaReport.get(0, "formation_change_fee")).isEqualByComparingTo(costFee);
        assertThat(new BigDecimal(response.get("formationChangeFee").asString())).isEqualByComparingTo(costFee);
        // 같은 턴 두 변경 뒤 목표: 편차는 SQL 이 기록 시각 최근 변경으로, Java 가 번호 최대 변경으로 목표를 잡아도 같다(위 비교에 포함)
        assertThat(sqlReport.get(0, "formation_deviation")).isNotNull();
        assertThat(javaReport.get(0, "formation_deviation")).isNotNull();

        // 출발점 세 열의 반대쪽 단언: SQL 은 RANKING 출발점이 있어 독려 비중·플랫폼 몫이 양수, Java 는 전부 DIRECT 라 0
        assertThat(((BigDecimal) sqlReport.get(0, "nudge_share")).signum()).as("SQL 독려 비중").isPositive();
        assertThat(((BigDecimal) sqlReport.get(0, "nudge_same_turn_share")).signum()).as("SQL 같은 턴 독려 비중").isPositive();
        assertThat(((BigDecimal) sqlReport.get(0, "platform_nudge_fee")).signum()).as("SQL 플랫폼 몫").isPositive();
        assertThat(((BigDecimal) javaReport.get(0, "nudge_share")).signum()).as("Java 독려 비중").isZero();
        assertThat(((BigDecimal) javaReport.get(0, "nudge_same_turn_share")).signum()).as("Java 같은 턴 독려 비중").isZero();
        assertThat(((BigDecimal) javaReport.get(0, "platform_nudge_fee")).signum()).as("Java 플랫폼 몫").isZero();
        RowSet expected = harness.readRows(sqlDb, new RowQuery("platform_fee", """
                SELECT coalesce(sum(e.fee), 0) AS fee FROM orders o JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :sid AND o.turn_no >= 1 AND o.turn_no < (SELECT current_turn_no FROM season WHERE id = :sid)
                  AND o.source IN ('RANKING', 'ALERT')""", List.of(new RowColumn("fee", ColumnType.DECIMAL))), sqlSeason);
        assertThat((BigDecimal) sqlReport.get(0, "platform_nudge_fee")).as("SQL 플랫폼 몫 = RANKING·ALERT 체결 수수료")
                .isEqualByComparingTo((BigDecimal) expected.get(0, "fee"));
    }
}
