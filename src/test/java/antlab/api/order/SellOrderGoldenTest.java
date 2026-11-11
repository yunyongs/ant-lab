package antlab.api.order;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationClassification;
import antlab.scoring.ReconciliationService;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import antlab.season.SeedGenerator;
import antlab.support.ComparisonSpec;
import antlab.support.DeferredPathSource;
import antlab.support.GoldenRowQueries;
import antlab.support.GoldenRowQueries.GoldenTable;
import antlab.support.RuleResultAssert;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.SeasonReplayInput;
import antlab.support.SeasonReplayInput.ActionKind;
import antlab.support.SeasonReplayInput.ReplayAction;
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
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매도 골든(P0-36) — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 *
 * <p>SQL 기준은 {@code tradeGolden}(20 → 21 → 23 × 5 → 24 → 25 → 25 검증)이고, API 시즌은 같은 경로·시드로 0턴 팀을 사고
 * 턴 1..5 로 전환한 뒤 5턴의 주문 4건(① 부분 매도 · ③ 매수 · ④ 전량 매도 · ⑤ 추가 매수)을 SQL 번호 순서로 보낸다.
 * ② 전술 변경과 ⑥ 라인업 변경은 재생하지 않는다(P0-37). 둘 다 현금·보유를 바꾸지 않으므로 {@code execution}·{@code cash_ledger}·
 * {@code holding}·{@code cash_account} 는 제외 열 없이 같아야 한다. {@code orders}·{@code lineup} 의 차이는 사유와 함께 빼고,
 * 빼도 차이를 숨기지 않게 반대쪽(정확한 건수·모양)을 따로 단언한다. 시즌을 6턴으로 넘기는 시험(T26b)은 맨 뒤에 둔다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("sampleAvailable")
class SellOrderGoldenTest {

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "trade_api");
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
    ReconciliationService reconciliation;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;
    /** 5턴 SQL 주문 키 4개: ① SELL a1 · ③ BUY b1 · ④ SELL c1 · ⑤ BUY d1 (턴/시장/종목/방향/순번). */
    private List<String> turnFiveKeys;

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        long started = System.nanoTime();
        TeamBuyGoldenSeason.Replayed r = golden.replayTrades(mvc, deferred, monthBlocks, SqlGoldenScenario.tradeGolden(),
                "trade_sql");
        System.out.println("[X1] 매도 골든 준비(SQL 기준 + 재생) 걸린 시간(ms) = " + (System.nanoTime() - started) / 1_000_000);
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        SeasonReplayInput input = harness.captureReplayInput(sqlDb, sqlSeason);
        turnFiveKeys = input.actions().stream().filter(a -> a.kind() == ActionKind.PLACE_ORDER)
                .map(ReplayAction::key).filter(k -> k.startsWith("5/")).toList();
        assertThat(turnFiveKeys).as("5턴 주문 4건").hasSize(4);
        assertThat(turnFiveKeys.stream().map(k -> k.split("/")[3]).toList())
                .as("① 매도 ③ 매수 ④ 매도 ⑤ 매수").containsExactly("SELL", "BUY", "SELL", "BUY");
        assertThat(r.orders()).as("재생한 주문 = SQL 의 모든 주문").isEqualTo(
                (int) harness.readRows(sqlDb, GoldenRowQueries.orders().query(), sqlSeason).size());
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private static String assetOf(String key) {
        String[] k = key.split("/");
        return k[1] + "/" + k[2];
    }

    private void assertSameRows(GoldenTable table) throws Exception {
        RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
        assertThat(expected.size()).as(table.name() + " (SQL)").isPositive();
        assertThat(actual.size()).as(table.name() + " (API)").isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, table.spec());
    }

    // ---- T23 ----

    @Test
    @Order(1)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T23_체결_원장_보유_계좌_턴_가격_턴이_제외_열_없이_정규화_행까지_같다() throws Exception {
        for (GoldenTable table : List.of(GoldenRowQueries.execution(), GoldenRowQueries.cashLedger(),
                GoldenRowQueries.holding(), GoldenRowQueries.cashAccount(), GoldenRowQueries.turnPrice(),
                GoldenRowQueries.turn())) {
            assertSameRows(table);
        }
        // 매도 체결 2건은 실현 손익이 있고 매수 체결은 없다(SQL 과 같은 키로 위 비교가 이미 확인했다 — 비어 있어 헛으로 일치하지 않게)
        RowQuery pnl = new RowQuery("pnl", """
                SELECT o.side, count(*) AS n, count(e.realized_pnl) AS with_pnl
                FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = :sid AND o.turn_no = 5
                GROUP BY o.side ORDER BY o.side""", List.of(new RowColumn("side", ColumnType.TEXT),
                new RowColumn("n", ColumnType.LONG), new RowColumn("with_pnl", ColumnType.LONG)));
        RowSet rows = harness.readRows(apiDb, pnl, apiSeason);
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0, "side")).isEqualTo("BUY");
        assertThat(rows.get(0, "n")).isEqualTo(2L);
        assertThat(rows.get(0, "with_pnl")).isEqualTo(0L);
        assertThat(rows.get(1, "side")).isEqualTo("SELL");
        assertThat(rows.get(1, "n")).isEqualTo(2L);
        assertThat(rows.get(1, "with_pnl")).isEqualTo(2L);
    }

    // ---- T24 ----

    @Test
    @Order(2)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T24_주문은_출발점_전술_변경_피드_열만_빼고_같고_빼도_차이를_숨기지_않는다() throws Exception {
        GoldenTable plain = GoldenRowQueries.orders();
        ComparisonSpec spec = ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status")
                .exclude("source", "P0-37 전술 변경·MVP 피드 출발점 — Phase 0 API 는 DIRECT 만 쓴다(Q5-A). 아래에서 SQL 쪽 모양을 따로 단언")
                .exclude("has_formation_change", "P0-37 전술 변경 — 재생하지 않으므로 SQL 의 변경 묶음이 Java 에는 없다. 아래에서 따로 단언")
                .exclude("has_turn_feed", "MVP 피드 출발점 — Phase 0 API 에 없다(Q5-A). 아래에서 따로 단언")
                .build();
        RowSet expected = harness.readRows(sqlDb, plain.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, plain.query(), apiSeason);
        assertThat(actual.size()).isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, spec);

        // 반대쪽 단언: SQL 5턴은 정확히 "FEED 1건(③) · 전술 변경에 묶인 3건(③④⑤)", 5턴 밖은 전부 DIRECT·묶임 없음·피드 없음. Java 는 전부 DIRECT·묶임 없음·피드 없음
        int sqlFeed = 0;
        int sqlBound = 0;
        int sqlFeedRef = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i, "turn_no").equals(5L)) {
                if ("FEED".equals(expected.get(i, "source"))) {
                    sqlFeed++;
                }
                if (Boolean.TRUE.equals(expected.get(i, "has_formation_change"))) {
                    sqlBound++;
                }
                if (Boolean.TRUE.equals(expected.get(i, "has_turn_feed"))) {
                    sqlFeedRef++;
                }
            } else {
                assertThat(expected.get(i, "source")).as("SQL 5턴 밖 출발점").isEqualTo("DIRECT");
                assertThat(expected.get(i, "has_formation_change")).isEqualTo(false);
                assertThat(expected.get(i, "has_turn_feed")).isEqualTo(false);
            }
        }
        assertThat(sqlFeed).as("SQL 5턴 FEED 주문").isEqualTo(1);
        assertThat(sqlBound).as("SQL 5턴 전술 변경에 묶인 주문").isEqualTo(3);
        assertThat(sqlFeedRef).as("SQL 5턴 피드 번호가 있는 주문").isEqualTo(1);
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i, "source")).isEqualTo("DIRECT");
            assertThat(actual.get(i, "has_formation_change")).isEqualTo(false);
            assertThat(actual.get(i, "has_turn_feed")).isEqualTo(false);
        }
    }

    // ---- T25 ----

    @Test
    @Order(3)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T25_라인업은_부분_매도_종목만_빼고_같고_그_종목은_각자의_모양이다() throws Exception {
        String a1 = assetOf(turnFiveKeys.get(0));
        GoldenTable table = GoldenRowQueries.lineup();
        RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
        List<List<Object>> sqlA1 = rowsOf(expected, a1);
        List<List<Object>> apiA1 = rowsOf(actual, a1);
        // SQL: ⑥ 이 닫고 다시 연 2행 — [0,5) 닫힘 + [5,∞) 미드필더. Java: 0턴 구간 1행이 열린 채(부분 매도는 구간을 닫지 않는다)
        assertThat(sqlA1).as("SQL a1 구간").hasSize(2);
        assertThat(apiA1).as("Java a1 구간").hasSize(1);
        int start = expected.indexOf("start_turn");
        int end = expected.indexOf("end_turn");
        int position = expected.indexOf("declared_position");
        assertThat(sqlA1.stream().map(r -> r.get(start) + ":" + r.get(end)).toList()).containsExactlyInAnyOrder("0:5", "5:null");
        assertThat(sqlA1.stream().filter(r -> r.get(end) == null).findFirst().orElseThrow().get(position)).isEqualTo("MIDFIELDER");
        assertThat(apiA1.get(0).get(start)).isEqualTo(0L);
        assertThat(apiA1.get(0).get(end)).isNull();
        Object sqlA1Declared = sqlA1.stream().filter(r -> Long.valueOf(0L).equals(r.get(start))).findFirst().orElseThrow().get(position);
        assertThat(apiA1.get(0).get(position)).as("Java a1 구간의 선언 = SQL a1 의 0턴 구간 선언(부분 매도는 선언을 바꾸지 않는다)").isEqualTo(sqlA1Declared);

        removeAsset(expected, a1);
        removeAsset(actual, a1);
        assertThat(actual.size()).isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, table.spec());
        // c1(전량 매도)은 닫힌 구간 [0,5), b1 은 새 구간 [5,∞) 로 위 비교에 포함돼 있다 — 비어 있어 헛으로 일치하지 않게
        String c1 = assetOf(turnFiveKeys.get(2));
        String b1 = assetOf(turnFiveKeys.get(1));
        assertThat(rowsOf(actual, c1).stream().map(r -> r.get(start) + ":" + r.get(end)).toList()).containsExactly("0:5");
        assertThat(rowsOf(actual, b1).stream().map(r -> r.get(start) + ":" + r.get(end)).toList()).containsExactly("5:null");
    }

    private static List<List<Object>> rowsOf(RowSet rows, String asset) {
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if ((rows.get(i, "market") + "/" + rows.get(i, "ticker")).equals(asset)) {
                out.add(rows.rows().get(i));
            }
        }
        return out;
    }

    private static void removeAsset(RowSet rows, String asset) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if ((rows.get(i, "market") + "/" + rows.get(i, "ticker")).equals(asset)) {
                rows.removeRow(i);
            }
        }
    }

    // ---- T26 ----

    private static final RowQuery RULES = new RowQuery("recon_rules", """
            SELECT rule_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count,
                   classification, detail
            FROM pg_temp.recon_rules(:sid, 5::smallint) ORDER BY rule_no""", List.of(
            new RowColumn("rule_no", ColumnType.LONG), new RowColumn("check_code", ColumnType.TEXT),
            new RowColumn("status", ColumnType.TEXT), new RowColumn("expected_value", ColumnType.DECIMAL),
            new RowColumn("actual_value", ColumnType.DECIMAL), new RowColumn("diff_value", ColumnType.DECIMAL),
            new RowColumn("mismatch_count", ColumnType.LONG), new RowColumn("classification", ColumnType.TEXT),
            new RowColumn("detail", ColumnType.JSON)));

    @Test
    @Order(4)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T26_API_시즌의_수동_대사_15개가_SQL_정의와_같고_모두_일치한다() throws Exception {
        RowSet rows = harness.queryWithDefinitions(apiDb, SqlGoldenScenario.RECONCILE_DEFINITIONS, RULES, apiSeason);
        List<RuleResult> sql = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object detail = rows.get(i, "detail");
            String classification = (String) rows.get(i, "classification");
            sql.add(new RuleResult(((Long) rows.get(i, "rule_no")).intValue(),
                    CheckCode.valueOf((String) rows.get(i, "check_code")),
                    ReconciliationStatus.valueOf((String) rows.get(i, "status")),
                    (BigDecimal) rows.get(i, "expected_value"), (BigDecimal) rows.get(i, "actual_value"),
                    (BigDecimal) rows.get(i, "diff_value"), ((Long) rows.get(i, "mismatch_count")).intValue(),
                    classification == null ? null : ReconciliationClassification.valueOf(classification),
                    detail == null ? null : detail.toString()));
        }
        List<RuleResult> java = new TransactionTemplate(txManager).execute(s -> reconciliation.evaluateManual(apiSeason, 5));
        assertThat(sql).hasSize(15);
        assertThat(java).hasSize(15);
        RuleResultAssert.assertSame(sql, java);
        assertThat(RuleResultAssert.mismatchCodes(java)).as("재생한 매도·매수 뒤 15개 모두 일치").isEmpty();
    }

    @Test
    @Order(5)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T26b_Java_시즌을_5턴에서_6턴으로_넘기면_17개가_일치하고_전량_매도_종목은_보유_목록에서_빠진다() throws Exception {
        String c1 = assetOf(turnFiveKeys.get(2));
        MockHttpServletResponse r = advance(mvc, apiSeason, 5);
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 6 AND attempt_no = 1 AND status = 'MATCH'",
                Long.class, apiSeason)).isEqualTo(17L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                Long.class, apiSeason)).isZero();
        JsonNode pool = JsonMapper.shared().readTree(mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString())
                .get("assets");
        long c1Id = -1;
        for (JsonNode a : pool) {
            if ((a.get("market").asString() + "/" + a.get("ticker").asString()).equals(c1)) {
                c1Id = a.get("assetId").asLong();
            }
        }
        assertThat(c1Id).isPositive();
        JsonNode holdings = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/seasons/" + apiSeason + "/holdings").exchange().getResponse().getContentAsString());
        for (JsonNode h : holdings.get("holdings")) {
            assertThat(h.get("assetId").asLong()).isNotEqualTo(c1Id);
        }
        assertThat(holdings.get("turnNo").asInt()).isEqualTo(6);
    }
}
