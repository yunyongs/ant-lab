package antlab.api.order;

import static antlab.api.turn.TurnApiSupport.advance;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 전술 변경 골든(P0-37) — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>SQL 기준은 매도 골든과 같은 {@code tradeGolden}(20 → 21 → 23 × 5 → 24 → 25 → 25 검증)이다. API 시즌은 25 의 행동 6건
 * (① 부분 매도 · ② 전술 변경 · ③ 매수 · ④ 전량 매도 · ⑤ 추가 매수 · ⑥ 라인업 변경)을 SQL 순서로 재생한다 — 전술 변경은 POST,
 * 라인업 변경은 내부 서비스. 전술 변경 이력·현재 포메이션·주문별 변경 연결(논리 키)·변경 비용이 SQL 과 같고, 이어서 SQL·API 를 10턴까지
 * 진행해 마지막 전환의 대사 17개가 일치하며 열한 표가 같음을 본다. 10턴 완주 시험은 시즌을 끝내므로 맨 뒤에 둔다.
 *
 * <p>이 골든의 변경은 1회뿐이라 Java 의 "같은 턴 마지막 변경 = 번호 최대" 와 SQL 의 {@code tr_last_change}("기록 시각 최근")가
 * 같은 결과를 낸다는 증거로는 약하다 — 같은 턴 여러 변경에서의 동치는 {@code FormationBindMutationTest} 의 기준 시즌(변경 3개)이 보이고,
 * 두 순서를 일부러 어긋나게 한 변조 데이터는 같은 시험이 규칙 16 으로 잡는다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("sampleAvailable")
class FormationChangeGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "change_api");
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
        TeamBuyGoldenSeason.Replayed r = golden.replayTrades(mvc, deferred, monthBlocks, SqlGoldenScenario.tradeGolden(),
                "change_sql", (season, turn, asset, position) -> lineupChanges.change(season, turn, asset, Position.valueOf(position)));
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private void assertSameRows(GoldenTable table) throws Exception {
        RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
        assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
        assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, table.spec());
    }

    // ---- T15 이력과 현재 포메이션 ----

    @Test
    @Order(1)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T15_전술_변경_이력과_시즌의_현재_포메이션이_정규화_행까지_같다() throws Exception {
        assertSameRows(GoldenRowQueries.formationChange());
        assertSameRows(GoldenRowQueries.season());
        RowSet changes = harness.readRows(apiDb, GoldenRowQueries.formationChange().query(), apiSeason);
        assertThat(changes.size()).as("25 는 변경 1회를 한다").isEqualTo(1);
        assertThat(changes.get(0, "turn_no")).isEqualTo(5L);
        assertThat(changes.get(0, "reason")).as("사유 원문 보존").isEqualTo("5턴 뒤 수비를 줄이고 공격을 늘림");
        // 사슬: 현재 = 마지막 변경의 이후 값 (규칙 15 의 핵심을 양쪽에서 직접 확인)
        for (DatabaseHandle db : List.of(sqlDb, apiDb)) {
            long season = db == sqlDb ? sqlSeason : apiSeason;
            RowSet chain = harness.readRows(db, new RowQuery("chain", """
                    SELECT count(*) AS n FROM season s JOIN formation_change fc ON fc.season_id = s.id
                    WHERE s.id = :sid AND (fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward)
                        = (s.current_goalkeeper, s.current_defender, s.current_midfielder, s.current_forward)
                      AND (fc.before_goalkeeper, fc.before_defender, fc.before_midfielder, fc.before_forward)
                        = (s.start_goalkeeper, s.start_defender, s.start_midfielder, s.start_forward)""",
                    List.of(new RowColumn("n", ColumnType.LONG))), season);
            assertThat(chain.get(0, "n")).isEqualTo(1L);
        }
    }

    // ---- T16 변경 비용 ----

    private static final RowQuery SQL_COSTS = new RowQuery("formation_costs", """
            SELECT c.turn_no, row_number() OVER (PARTITION BY c.turn_no ORDER BY c.formation_change_id) AS nth,
                   c.order_count, c.buy_notional, c.sell_notional, c.fee_sum, c.tax_sum
            FROM pg_temp.formation_costs(:sid) c ORDER BY c.formation_change_id""", List.of(
            new RowColumn("turn_no", ColumnType.LONG), new RowColumn("nth", ColumnType.LONG),
            new RowColumn("order_count", ColumnType.LONG), new RowColumn("buy_notional", ColumnType.DECIMAL),
            new RowColumn("sell_notional", ColumnType.DECIMAL), new RowColumn("fee_sum", ColumnType.DECIMAL),
            new RowColumn("tax_sum", ColumnType.DECIMAL)));

    @Test
    @Order(2)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T16_변경_비용_조회가_SQL_formation_costs와_같고_변경에_묶인_세_주문을_센다() throws Exception {
        RowSet sql = harness.queryWithDefinitions(sqlDb, SqlGoldenScenario.TRADE_DEFINITIONS, SQL_COSTS, sqlSeason);
        List<FormationCost> java = costQuery.bySeason(apiSeason);
        assertThat(sql.size()).isEqualTo(1);
        assertThat(java).hasSize(1);
        FormationCost c = java.get(0);
        assertThat(c.turnNo()).isEqualTo(((Long) sql.get(0, "turn_no")).intValue());
        assertThat(c.orderCount()).as("변경 뒤 주문 ③④⑤").isEqualTo(3L).isEqualTo(sql.get(0, "order_count"));
        assertThat(c.buyNotional()).isEqualByComparingTo((BigDecimal) sql.get(0, "buy_notional"));
        assertThat(c.sellNotional()).isEqualByComparingTo((BigDecimal) sql.get(0, "sell_notional"));
        assertThat(c.feeSum()).isEqualByComparingTo((BigDecimal) sql.get(0, "fee_sum"));
        assertThat(c.taxSum()).isEqualByComparingTo((BigDecimal) sql.get(0, "tax_sum"));
        // 비어 있어 헛으로 일치하지 않게: 매수·매도 대금과 수수료가 모두 양수다
        assertThat(c.buyNotional().signum()).isPositive();
        assertThat(c.sellNotional().signum()).isPositive();
        assertThat(c.feeSum().signum()).isPositive();
        // 이중 계산 방지: 비용 합은 execution 에서만 오고, 같은 주문들의 원장 FEE 합과 같다(원장은 독립 대조일 뿐 더하지 않는다)
        BigDecimal ledgerFee = jdbc.queryForObject("""
                SELECT coalesce(sum(-l.amount), 0) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id
                WHERE l.season_id = ? AND l.type = 'FEE' AND o.formation_change_id = ?""", BigDecimal.class, apiSeason,
                c.formationChangeId());
        assertThat(ledgerFee).isEqualByComparingTo(c.feeSum());
    }

    // ---- T17 10턴 완주 ----

    @Test
    @Order(3)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T17_변경이_낀_시즌을_10턴까지_넘기면_마지막_전환의_대사_17개가_일치하고_열한_표가_SQL과_같다() throws Exception {
        harness.execute(sqlDb, SqlGoldenScenario.advanceTurns(SqlGoldenScenario.PATH_LENGTH - 5));
        for (int turn = 5; turn < SqlGoldenScenario.PATH_LENGTH; turn++) {
            MockHttpServletResponse r = advance(mvc, apiSeason, turn);
            assertThat(r.getStatus()).as("전환 " + (turn + 1)).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ? AND attempt_no = 1 AND status = 'MATCH'",
                Long.class, apiSeason, SqlGoldenScenario.PATH_LENGTH)).as("마지막 전환의 대사 17개").isEqualTo(17L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                Long.class, apiSeason)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, apiSeason)).isEqualTo("FINISHED");

        RowSet sqlAssets = harness.readRows(sqlDb, GoldenRowQueries.assetKeys(), sqlSeason);
        RowSet apiAssets = harness.readRows(apiDb, GoldenRowQueries.assetKeys(), apiSeason);
        ComparisonSpec ordersSpec = ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status", "formation_change_key")
                .exclude("source", "MVP 피드 출발점 — Phase 0 API 는 DIRECT 만 쓴다(Q5-A). SQL 5턴 FEED 1건은 매도 골든이 따로 단언한다")
                .exclude("has_turn_feed", "MVP 피드 출발점 — Phase 0 API 에 없다(Q5-A)")
                .build();
        // 제외한 두 열의 반대쪽 단언(매도 골든 T24 와 같은 모양, 같은 시험 안에서): SQL 은 FEED 1건·피드 참조 1건, Java 는 전부 DIRECT·참조 0건
        RowSet sqlOrders = harness.readRows(sqlDb, GoldenRowQueries.orders().query(), sqlSeason);
        RowSet apiOrders = harness.readRows(apiDb, GoldenRowQueries.orders().query(), apiSeason);
        int sqlFeed = 0;
        int sqlFeedRef = 0;
        for (int i = 0; i < sqlOrders.size(); i++) {
            sqlFeed += "FEED".equals(sqlOrders.get(i, "source")) ? 1 : 0;
            sqlFeedRef += Boolean.TRUE.equals(sqlOrders.get(i, "has_turn_feed")) ? 1 : 0;
        }
        assertThat(sqlFeed).as("SQL FEED 주문").isEqualTo(1);
        assertThat(sqlFeedRef).as("SQL 피드 번호가 있는 주문").isEqualTo(1);
        assertThat(apiOrders.size()).isEqualTo(sqlOrders.size());
        for (int i = 0; i < apiOrders.size(); i++) {
            assertThat(apiOrders.get(i, "source")).as("Java 출발점").isEqualTo("DIRECT");
            assertThat(apiOrders.get(i, "has_turn_feed")).as("Java 피드 참조").isEqualTo(false);
        }
        List<GoldenTable> tables = new ArrayList<>();
        for (GoldenTable t : GoldenRowQueries.allForDrillGolden()) {
            tables.add(t.name().equals("orders") ? new GoldenTable(t.query(), ordersSpec) : t);
        }
        tables.add(GoldenRowQueries.formationChange());
        tables.add(GoldenRowQueries.execution());
        tables.add(GoldenRowQueries.holding());
        assertThat(tables.stream().map(GoldenTable::name).toList()).contains("formation_change", "orders", "lineup",
                "reconciliation_log", "turn", "turn_price", "cash_account", "season");
        for (GoldenTable table : tables) {
            RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
            RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
            if (table.name().equals("reconciliation_log")) {
                expected = GoldenRowQueries.normalizeLogDetail(expected, sqlAssets);
                actual = GoldenRowQueries.normalizeLogDetail(actual, apiAssets);
            }
            assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
            assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
            RowSetDiff.assertNoDifference(expected, actual, table.spec());
        }
    }
}
