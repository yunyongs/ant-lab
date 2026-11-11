package antlab.api.order;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationClassification;
import antlab.scoring.ReconciliationService;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import antlab.asset.Position;
import antlab.season.LineupChangeService;
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
import antlab.support.SeasonReplayInput.PlaceOrder;
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
 * 턴 1..5 로 전환한 뒤 5턴의 행동 6건(① 부분 매도 · ② 전술 변경 · ③ 매수 · ④ 전량 매도 · ⑤ 추가 매수 · ⑥ 라인업 변경)을 SQL 순서로
 * 보낸다(P0-37 부터 전술 변경·라인업 변경도 재생한다). {@code execution}·{@code cash_ledger}·{@code holding}·{@code cash_account}·
 * {@code lineup} 은 제외 열 없이, {@code orders} 는 피드 출발점 열({@code source}·{@code turn_feed_id})만 빼고 같아야 한다 —
 * 주문의 전술 변경 연결은 (턴, 변경 순번) 논리 키로 비교한다. 빼는 피드 열은 반대쪽(SQL 5턴 FEED 1건, Java 전부 DIRECT)을 따로 단언한다.
 * 시즌을 6턴으로 넘기는 시험(T26b)은 맨 뒤에 둔다. 전술 변경 이력·비용·10턴 완주는 {@code FormationChangeGoldenTest} 가 본다.
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
    @Autowired
    LineupChangeService lineupChanges;

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
                "trade_sql", (season, turn, asset, position) -> lineupChanges.change(season, turn, asset, Position.valueOf(position)));
        System.out.println("[X1] 매도 골든 준비(SQL 기준 + 재생) 걸린 시간(ms) = " + (System.nanoTime() - started) / 1_000_000);
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        SeasonReplayInput input = harness.captureReplayInput(sqlDb, sqlSeason);
        turnFiveKeys = input.actions().stream().filter(a -> a instanceof PlaceOrder).map(a -> ((PlaceOrder) a).key())
                .filter(k -> k.startsWith("5/")).toList();
        // 5턴의 행동 순서는 SQL 시나리오 ①–⑥ 그대로다(전술 변경은 ① 뒤·③ 앞, 라인업 변경은 맨 끝)
        int five = input.actions().indexOf(new SeasonReplayInput.AdvanceTurn(5));
        assertThat(five).isPositive();
        assertThat(input.actions().subList(five + 1, input.actions().size()).stream().map(ReplayAction::kind).toList()).as("5턴 행동 종류 순서").containsExactly(ActionKind.PLACE_ORDER, ActionKind.FORMATION_CHANGE,
                ActionKind.PLACE_ORDER, ActionKind.PLACE_ORDER, ActionKind.PLACE_ORDER, ActionKind.LINEUP_CHANGE);
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
    void T24_주문은_피드_출발점_열만_빼고_같고_전술_변경_연결은_논리_키까지_같다() throws Exception {
        GoldenTable plain = GoldenRowQueries.orders();
        ComparisonSpec spec = ComparisonSpec.named("orders").keys("turn_no", "market", "ticker", "side", "nth")
                .values("type", "quantity", "limit_price", "status", "formation_change_key")
                .exclude("source", "MVP 피드 출발점 — Phase 0 API 는 DIRECT 만 쓴다(Q5-A). 아래에서 SQL 쪽 모양을 따로 단언")
                .exclude("has_turn_feed", "MVP 피드 출발점 — Phase 0 API 에 없다(Q5-A). 아래에서 따로 단언")
                .build();
        RowSet expected = harness.readRows(sqlDb, plain.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, plain.query(), apiSeason);
        assertThat(actual.size()).isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, spec);

        // 반대쪽 단언: SQL 5턴은 정확히 "FEED 1건(③) · 전술 변경에 묶인 3건(③④⑤, 변경 5/1)", 5턴 밖은 전부 DIRECT·묶임 없음·피드 없음.
        // Java 는 전부 DIRECT·피드 없음이고, 전술 변경 연결은 SQL 과 같다(위 비교가 키까지 확인했다 — 비어 있어 헛으로 일치하지 않게 건수도 본다)
        int sqlFeed = 0;
        int sqlFeedRef = 0;
        List<String> sqlBound = new ArrayList<>();
        List<String> javaBound = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i, "turn_no").equals(5L)) {
                if ("FEED".equals(expected.get(i, "source"))) {
                    sqlFeed++;
                }
                if (Boolean.TRUE.equals(expected.get(i, "has_turn_feed"))) {
                    sqlFeedRef++;
                }
            } else {
                assertThat(expected.get(i, "source")).as("SQL 5턴 밖 출발점").isEqualTo("DIRECT");
                assertThat(expected.get(i, "formation_change_key")).isNull();
                assertThat(expected.get(i, "has_turn_feed")).isEqualTo(false);
            }
            if (expected.get(i, "formation_change_key") != null) {
                sqlBound.add(expected.get(i, "formation_change_key").toString());
            }
        }
        assertThat(sqlFeed).as("SQL 5턴 FEED 주문").isEqualTo(1);
        assertThat(sqlFeedRef).as("SQL 5턴 피드 번호가 있는 주문").isEqualTo(1);
        assertThat(sqlBound).as("SQL 5턴 전술 변경에 묶인 주문").containsExactly("5/1", "5/1", "5/1");
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i, "source")).isEqualTo("DIRECT");
            assertThat(actual.get(i, "has_turn_feed")).isEqualTo(false);
            if (actual.get(i, "formation_change_key") != null) {
                javaBound.add(actual.get(i, "formation_change_key").toString());
            }
        }
        assertThat(javaBound).as("Java 5턴 전술 변경에 묶인 주문").containsExactly("5/1", "5/1", "5/1");
    }

    // ---- T25 ----

    @Test
    @Order(3)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void T25_라인업은_제외_열_없이_구간까지_같고_포지션_변경_종목은_닫고_다시_연_두_구간이다() throws Exception {
        String a1 = assetOf(turnFiveKeys.get(0));
        GoldenTable table = GoldenRowQueries.lineup();
        RowSet expected = harness.readRows(sqlDb, table.query(), sqlSeason);
        RowSet actual = harness.readRows(apiDb, table.query(), apiSeason);
        assertThat(actual.size()).isEqualTo(expected.size());
        RowSetDiff.assertNoDifference(expected, actual, table.spec());

        // 비어 있어 헛으로 일치하지 않게 모양을 따로 본다: a1(부분 매도 + 포지션 변경)은 [0,5) 닫힘 + [5,∞) 미드필더 두 구간,
        // c1(전량 매도)은 닫힌 구간 [0,5), b1(변경 뒤 신규 매수)은 새 구간 [5,∞)
        int start = actual.indexOf("start_turn");
        int end = actual.indexOf("end_turn");
        int position = actual.indexOf("declared_position");
        List<List<Object>> javaA1 = rowsOf(actual, a1);
        assertThat(javaA1.stream().map(r -> r.get(start) + ":" + r.get(end)).toList()).containsExactlyInAnyOrder("0:5", "5:null");
        assertThat(javaA1.stream().filter(r -> r.get(end) == null).findFirst().orElseThrow().get(position)).isEqualTo("MIDFIELDER");
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
