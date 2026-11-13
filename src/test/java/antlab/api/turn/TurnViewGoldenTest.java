package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.season.SeedGenerator;
import antlab.support.ComparisonSpec;
import antlab.support.DefaultBearerConfig;
import antlab.support.DeferredPathSource;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.RowSetDiff;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestTokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 조회·보유 조회 골든 — 실제 표본 CSV 가 있을 때만 돈다(없으면 건너뜀, 환경 변수로 지정했는데 없으면 실패).
 * SQL 기준은 {@code TURN_GOLDEN}(20 → 21 → 23 × 10)이다. 기대 표는 SQL 시즌에 {@code db/26_season_report_def.sql} 을 읽고
 * 그 정수 원천 {@code sr_turn_base} 에서 {@code sr_div8}(정수 ÷ 정수, 소수 8자리 HALF_UP, 반올림 한 번)로 만든다 —
 * 기간 수익률·위험 여유·위험 넘음·포지션 비중·목표 대비 차이·이탈. 실제는 같은 경로·시드·0턴 팀으로 만든 API 시즌에
 * {@code POST …/turns} 를 10번 보낸 뒤의 {@code GET …/turns/1..10} 이다. 보유는 SQL 의 {@code holding ⋈ turn_price(10) ⋈ 열린 lineup}
 * (자연 키 시장·종목 코드)과 {@code GET …/holdings} 를 행 단위로 비교한다. 22 수동 대사는 끼우지 않는다. 실패 메시지에 값이 없다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("sampleAvailable")
class TurnViewGoldenTest {

    static final String REPORT_DEFINITIONS = "db/26_season_report_def.sql";

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "turn_view_api");
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

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private DatabaseHandle sqlDb;
    private long sqlSeason;
    private long apiSeason;

    private static RowColumn col(String name, ColumnType type) {
        return new RowColumn(name, type);
    }

    private static final String[] POSITIONS = {"goalkeeper", "defender", "midfielder", "forward"};
    private static final String[] TURN_DECIMALS = {"total_value", "previous_total_value", "value_change", "period_return",
            "risk_limit", "risk_headroom", "goalkeeper_weight", "defender_weight", "midfielder_weight", "forward_weight",
            "goalkeeper_gap", "defender_gap", "midfielder_gap", "forward_gap", "formation_deviation"};

    /** 기대 턴 표: sr_turn_base 의 정수 원천에서 시험 쪽 질의가 sr_div8 로 만든다. */
    private static RowQuery expectedTurns() {
        String sql = """
                SELECT b.turn_no::bigint AS turn_no, b.total_value AS total_value, b.prev_value AS previous_total_value,
                       b.total_value - b.prev_value AS value_change,
                       pg_temp.sr_div8(b.total_value - b.prev_value, b.prev_value) AS period_return,
                       se.risk_limit AS risk_limit, b.drawdown - se.risk_limit AS risk_headroom,
                       (b.drawdown < se.risk_limit) AS risk_breached,
                       pg_temp.sr_div8(tn.goalkeeper_value, b.total_value) AS goalkeeper_weight,
                       pg_temp.sr_div8(tn.defender_value, b.total_value) AS defender_weight,
                       pg_temp.sr_div8(tn.midfielder_value, b.total_value) AS midfielder_weight,
                       pg_temp.sr_div8(tn.forward_value, b.total_value) AS forward_weight,
                       b.target_goalkeeper::bigint AS target_goalkeeper, b.target_defender::bigint AS target_defender,
                       b.target_midfielder::bigint AS target_midfielder, b.target_forward::bigint AS target_forward,
                       pg_temp.sr_div8(100 * tn.goalkeeper_value - b.target_goalkeeper * b.total_value, 100 * b.total_value) AS goalkeeper_gap,
                       pg_temp.sr_div8(100 * tn.defender_value - b.target_defender * b.total_value, 100 * b.total_value) AS defender_gap,
                       pg_temp.sr_div8(100 * tn.midfielder_value - b.target_midfielder * b.total_value, 100 * b.total_value) AS midfielder_gap,
                       pg_temp.sr_div8(100 * tn.forward_value - b.target_forward * b.total_value, 100 * b.total_value) AS forward_gap,
                       pg_temp.sr_div8(b.dev_num, b.dev_den) AS formation_deviation
                FROM pg_temp.sr_turn_base(:sid) b
                JOIN turn tn ON tn.season_id = :sid AND tn.turn_no = b.turn_no
                JOIN season se ON se.id = :sid
                ORDER BY b.turn_no""";
        List<RowColumn> columns = new ArrayList<>();
        columns.add(col("turn_no", ColumnType.LONG));
        for (String name : TURN_DECIMALS) {
            columns.add(col(name, ColumnType.DECIMAL));
            if (name.equals("risk_headroom")) {
                columns.add(col("risk_breached", ColumnType.BOOLEAN));
            }
            if (name.equals("forward_weight")) {
                for (String p : POSITIONS) {
                    columns.add(col("target_" + p, ColumnType.LONG));
                }
            }
        }
        return new RowQuery("view_turns", sql, columns);
    }

    private static ComparisonSpec turnSpec(RowQuery query) {
        List<String> values = new ArrayList<>(query.columns().stream().map(RowColumn::name).toList());
        values.remove("turn_no");
        return ComparisonSpec.named("view_turns").keys("turn_no").values(values.toArray(new String[0])).build();
    }

    private static final RowQuery EXPECTED_HOLDINGS = new RowQuery("view_holdings", """
            SELECT a.market, a.ticker, h.quantity::bigint AS quantity, h.avg_price AS avg_price, tp.price AS price,
                   l.declared_position AS declared_position, h.quantity * tp.price AS value
            FROM holding h
            JOIN asset a ON a.id = h.asset_id
            JOIN turn_price tp ON tp.season_id = h.season_id AND tp.asset_id = h.asset_id
                              AND tp.turn_no = (SELECT current_turn_no FROM season WHERE id = :sid)
            JOIN lineup l ON l.season_id = h.season_id AND l.asset_id = h.asset_id AND l.end_turn IS NULL
            WHERE h.season_id = :sid AND h.quantity > 0
            ORDER BY a.market, a.ticker""", List.of(col("market", ColumnType.TEXT), col("ticker", ColumnType.TEXT),
            col("quantity", ColumnType.LONG), col("avg_price", ColumnType.DECIMAL), col("price", ColumnType.DECIMAL),
            col("declared_position", ColumnType.TEXT), col("value", ColumnType.DECIMAL)));

    private static final RowQuery EXPECTED_TOTALS = new RowQuery("view_totals", """
            SELECT 'now' AS scope, tn.cash_balance AS cash_balance, tn.holdings_value AS holdings_value,
                   tn.total_value AS total_value, tn.goalkeeper_value AS goalkeeper_value,
                   tn.defender_value AS defender_value, tn.midfielder_value AS midfielder_value,
                   tn.forward_value AS forward_value
            FROM turn tn WHERE tn.season_id = :sid AND tn.turn_no = (SELECT current_turn_no FROM season WHERE id = :sid)""",
            List.of(col("scope", ColumnType.TEXT), col("cash_balance", ColumnType.DECIMAL),
                    col("holdings_value", ColumnType.DECIMAL), col("total_value", ColumnType.DECIMAL),
                    col("goalkeeper_value", ColumnType.DECIMAL), col("defender_value", ColumnType.DECIMAL),
                    col("midfielder_value", ColumnType.DECIMAL), col("forward_value", ColumnType.DECIMAL)));

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        TeamBuyGoldenSeason.Replayed r = golden.replay(mvc, deferred, monthBlocks, SqlGoldenScenario.turnGolden(),
                "turn_view_sql");
        sqlDb = r.sqlDb();
        sqlSeason = r.sqlSeason();
        apiSeason = r.apiSeason();
        for (int t = 0; t < SqlGoldenScenario.PATH_LENGTH; t++) {
            MockHttpServletResponse res = advance(mvc, apiSeason, t);
            assertThat(res.getStatus()).as("전환 " + t).isEqualTo(201);
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private static Object cell(JsonNode o, String json, ColumnType type) {
        JsonNode n = o.get(json);
        assertThat(n).as("응답 필드 " + json).isNotNull();
        return switch (type) {
            case DECIMAL -> new BigDecimal(n.asString());
            case LONG -> n.asLong();
            case BOOLEAN -> n.asBoolean();
            default -> n.asString();
        };
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }

    @Test
    @Order(1)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 턴_1부터_10까지_API_조회가_26_정의의_정수_원천_계산과_행_단위로_같다() throws Exception {
        RowQuery query = expectedTurns();
        RowSet expected = harness.queryWithDefinitions(sqlDb, REPORT_DEFINITIONS, query, sqlSeason);
        assertThat(expected.size()).as("SQL 기대 행").isEqualTo(SqlGoldenScenario.PATH_LENGTH);
        List<List<Object>> rows = new ArrayList<>();
        for (int n = 1; n <= SqlGoldenScenario.PATH_LENGTH; n++) {
            MockHttpServletResponse res = getTurn(mvc, apiSeason, n);
            assertThat(res.getStatus()).as("GET turns/" + n).isEqualTo(200);
            JsonNode o = json(res);
            List<Object> row = new ArrayList<>();
            for (RowColumn column : query.columns()) {
                String name = column.name();
                if (name.startsWith("target_")) {
                    row.add(o.get("targetFormation").get(name.substring("target_".length())).asLong());
                } else if (name.equals("turn_no")) {
                    row.add(o.get("turnNo").asLong());
                } else {
                    row.add(cell(o, camel(name), column.type()));
                }
            }
            rows.add(row);
        }
        RowSet actual = new RowSet(query.columns(), rows);
        RowSetDiff.assertNoDifference(expected, actual, turnSpec(query));
    }

    @Test
    @Order(2)
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 열_번_뒤_보유_조회가_SQL의_보유_가격_열린_라인업_조인과_행_단위로_같다() throws Exception {
        MockHttpServletResponse res = getHoldings(mvc, apiSeason);
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode o = json(res);
        assertThat(o.get("turnNo").asInt()).isEqualTo(SqlGoldenScenario.PATH_LENGTH);

        // 자연 키(시장·종목 코드)로 잇는다 — 대리 키는 DB마다 다르다.
        Map<Long, String> naturalKey = new HashMap<>();
        JsonNode pool = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString()).get("assets");
        pool.forEach(a -> naturalKey.put(a.get("assetId").asLong(), a.get("market").asString() + "/" + a.get("ticker").asString()));
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode h : o.get("holdings")) {
            String[] key = naturalKey.get(h.get("assetId").asLong()).split("/", 2);
            rows.add(List.of(key[0], key[1], h.get("quantity").asLong(), new BigDecimal(h.get("avgPrice").asString()),
                    new BigDecimal(h.get("price").asString()), h.get("declaredPosition").asString(),
                    new BigDecimal(h.get("value").asString())));
        }
        rows.sort((a, b) -> (a.get(0) + "/" + a.get(1)).compareTo(b.get(0) + "/" + b.get(1)));
        RowSet expected = harness.readRows(sqlDb, EXPECTED_HOLDINGS, sqlSeason);
        assertThat(expected.size()).as("SQL 보유 행").isPositive();
        RowSet actual = new RowSet(EXPECTED_HOLDINGS.columns(), rows);
        ComparisonSpec spec = ComparisonSpec.named("view_holdings").keys("market", "ticker")
                .values("quantity", "avg_price", "price", "declared_position", "value").build();
        RowSetDiff.assertNoDifference(expected, actual, spec);

        // 매매가 없는 현재 턴이라 총액·보유·포지션별 평가액·현금이 그 턴 turn 행과 같다
        RowSet totals = harness.readRows(sqlDb, EXPECTED_TOTALS, sqlSeason);
        RowSet actualTotals = new RowSet(EXPECTED_TOTALS.columns(), List.of(List.of("now",
                new BigDecimal(o.get("cashBalance").asString()), new BigDecimal(o.get("holdingsValue").asString()),
                new BigDecimal(o.get("totalValue").asString()), new BigDecimal(o.get("goalkeeperValue").asString()),
                new BigDecimal(o.get("defenderValue").asString()), new BigDecimal(o.get("midfielderValue").asString()),
                new BigDecimal(o.get("forwardValue").asString()))));
        RowSetDiff.assertNoDifference(totals, actualTotals, ComparisonSpec.named("view_totals").keys("scope")
                .values("cash_balance", "holdings_value", "total_value", "goalkeeper_value", "defender_value",
                        "midfielder_value", "forward_value").build());
    }
}
