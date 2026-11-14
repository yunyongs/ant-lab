package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.season.LineupChangeService;
import antlab.season.SeedGenerator;
import antlab.support.DefaultBearerConfig;
import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import antlab.support.SqlGoldenScenario;
import antlab.support.TeamBuyGoldenSeason;
import antlab.support.TestTokens;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성적표 골든 G3(P0-38, Java 단독) — SQL 로 재생할 수 없는 긴 시즌. 표본을 API DB 에 적재하고 실제 부트스트랩 경로(시드 고정, 길이 {@value #PATH})로
 * 시즌을 시작해 0턴 매수와 <b>0턴 매도</b>(P0-36 Q1), 중간 추가 매수, 같은 턴 전술 변경 둘, 주문 없는 변경, 라인업 변경, 같은 턴 매수 뒤 전량 매도를 낸 뒤
 * 끝까지 진행한다. 결과: API 응답 = 정의 {@code sr_report}(API DB) 37열 전부이고, 역할 수행이 실제로 판정된다({@code rolePlayersJudged > 0},
 * {@code roleFit} 값 있음). 0턴 수수료·0턴 대금 분리(P0-36 Q1)는 원천 조회와 독립한 SQL 합으로 따로 확인한다. 값(금액·가격)은 출력하지 않는다.
 */
@Tag("sql-golden")
@SpringBootTest(properties = {"antlab.single-member.email=phase0@example.invalid", "antlab.single-member.name=연습 감독"})
@AutoConfigureMockMvc
@Import({TestTokens.class, DefaultBearerConfig.class})
@ActiveProfiles({"test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("sampleAvailable")
class SeasonReportJavaOnlyGoldenTest {

    static final int PATH = 24;
    static final long SEED = 20261111L;

    private static TeamBuyGoldenSeason golden;

    static boolean sampleAvailable() {
        return TeamBuyGoldenSeason.sampleAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        golden = TeamBuyGoldenSeason.start(registry, "report_java_api");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoldenBeans {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> SEED;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    LineupChangeService lineupChanges;
    @Autowired
    JdbcTemplate jdbc;

    private SqlGoldenHarness harness;
    private DatabaseHandle apiDb;
    private long season;
    private long[] assets;
    private final Map<String, Long> ids = new LinkedHashMap<>();
    private final Map<Long, BigDecimal> prices0 = new LinkedHashMap<>();

    @AfterAll
    static void stop() throws Exception {
        if (golden != null) {
            golden.close();
        }
    }

    private MockHttpServletResponse post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
    }

    private void order(int turn, String side, long assetId, long quantity, String declared) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"side\": \"" + side + "\", \"assetId\": " + assetId + ", \"quantity\": "
                + quantity + (declared == null ? "" : ", \"declaredPosition\": \"" + declared + "\"") + "}";
        assertThat(post("/api/v1/seasons/" + season + "/orders", body).getStatus()).as(side + " 턴 " + turn).isEqualTo(201);
    }

    private void changeFormation(int turn, int gk, int df, int mf, int fw, String reason) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"formation\": {\"goalkeeper\": " + gk + ", \"defender\": " + df
                + ", \"midfielder\": " + mf + ", \"forward\": " + fw + "}, \"reason\": \"" + reason + "\"}";
        assertThat(post("/api/v1/seasons/" + season + "/formation-changes", body).getStatus()).as("전술 변경 턴 " + turn).isEqualTo(201);
    }

    private long quantityFor(long assetId, long budgetWon) {
        return Math.max(1, new BigDecimal(budgetWon).divide(prices0.get(assetId), 0, RoundingMode.DOWN).longValue());
    }

    @BeforeAll
    void prepare() throws Exception {
        harness = golden.harness();
        apiDb = golden.apiDb();
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        MockHttpServletResponse started = post("/api/v1/seasons", "{\"riskLimit\": \"-0.40\", \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 30, \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + PATH + "}");
        assertThat(started.getStatus()).as("시즌 시작").isEqualTo(201);
        season = harness.practiceSeasonId(apiDb);

        // 종목: 국내 넷(대용 069500 포함)·미국 넷(대용 SPY 포함)
        JsonNode pool = JsonMapper.shared().readTree(mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString())
                .get("assets");
        List<String> kr = new ArrayList<>();
        List<String> us = new ArrayList<>();
        pool.forEach(a -> {
            String key = a.get("market").asString() + "/" + a.get("ticker").asString();
            ids.put(key, a.get("assetId").asLong());
            (a.get("market").asString().equals("KR") ? kr : us).add(key);
        });
        List<String> chosen = new ArrayList<>(List.of("KR/069500", "US/SPY"));
        for (String k : kr) {
            if (chosen.size() < 5 && !chosen.contains(k)) {
                chosen.add(k);
            }
        }
        for (String k : us) {
            if (chosen.size() < 8 && !chosen.contains(k)) {
                chosen.add(k);
            }
        }
        assertThat(chosen).as("선택 종목").hasSizeGreaterThanOrEqualTo(8);
        JsonNode priceRows = JsonMapper.shared().readTree(mvc.get().uri("/api/v1/seasons/" + season + "/prices?turnNo=0")
                .exchange().getResponse().getContentAsString()).get("prices");
        priceRows.forEach(p -> prices0.put(p.get("assetId").asLong(), new BigDecimal(p.get("price").asString())));

        long[] a = chosen.stream().limit(8).mapToLong(ids::get).toArray();
        assets = a;
        Position[] declared = {Position.DEFENDER, Position.MIDFIELDER, Position.FORWARD_TARGET, Position.GOALKEEPER,
                Position.FORWARD_BREAKOUT, Position.DEFENDER, Position.MIDFIELDER, Position.FORWARD_TARGET};
        // 0턴: 다섯 종목 매수, 그중 하나는 일부 0턴 매도
        for (int i = 0; i < 5; i++) {
            order(0, "BUY", a[i], quantityFor(a[i], 3_000_000), declared[i].name());
        }
        order(0, "SELL", a[0], Math.max(1, quantityFor(a[0], 3_000_000) / 2), null);
        int turn = 0;
        for (; turn < 3; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
        }
        // 3턴: 새 종목 매수(변경 전), 같은 턴 전술 변경 둘, 변경 뒤 추가 매수(두 번째 변경에 묶임)
        order(3, "BUY", a[5], quantityFor(a[5], 2_000_000), declared[5].name());
        changeFormation(3, 10, 20, 40, 30, "첫째 변경");
        changeFormation(3, 15, 25, 30, 30, "둘째 변경");
        order(3, "BUY", a[0], quantityFor(a[0], 1_000_000), null);
        for (; turn < 6; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
        }
        // 6턴: 주문 없는 전술 변경, 라인업 변경
        changeFormation(6, 10, 30, 30, 30, "주문 없는 변경");
        lineupChanges.change(season, 6, a[1], Position.FORWARD_BREAKOUT);
        for (; turn < 9; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
        }
        // 9턴: 같은 턴 매수 뒤 전량 매도, 일부 매도
        long q7 = quantityFor(a[6], 1_500_000);
        order(9, "BUY", a[6], q7, declared[6].name());
        order(9, "SELL", a[6], q7, null);
        for (; turn < PATH; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).as("전환 " + (turn + 1)).isEqualTo(201);
        }
    }

    /** 서버 경로는 출발점을 DIRECT 로만 남기므로, 독려 집계를 양수로 만들려고 시험 안에서 출발점을 직접 덮어쓴다. */
    private long insertNudgeFeed(int turn) {
        return jdbc.queryForObject("INSERT INTO turn_feed (season_id, turn_no, seq, kind, template_id, body)"
                + " VALUES (?, ?, 99, 'NUDGE', 'golden-test', '시험용 독려') RETURNING id", Long.class, season, turn);
    }

    private void setSource(int turn, String side, long assetId, String source, Long feedId) {
        int updated = jdbc.update("UPDATE orders SET source = ?, turn_feed_id = ? WHERE season_id = ? AND turn_no = ? AND side = ?"
                + " AND asset_id = ?", source, feedId, season, turn, side, assetId);
        assertThat(updated).as("출발점을 바꾼 주문 (턴 " + turn + " " + side + ")").isEqualTo(1);
    }

    private long count(String sql) throws Exception {
        RowSet rows = harness.readRows(apiDb, new RowQuery("count", sql, List.of(new RowColumn("n", ColumnType.LONG))), season);
        return (Long) rows.get(0, "n");
    }

    private BigDecimal sum(String sql) throws Exception {
        RowSet rows = harness.readRows(apiDb, new RowQuery("sum", sql, List.of(new RowColumn("v", ColumnType.DECIMAL))), season);
        return (BigDecimal) rows.get(0, "v");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void G3_Java_단독_긴_시즌의_응답이_정의_결과와_서른일곱_열_모두_같고_역할_수행이_판정된다() throws Exception {
        RowSet java = SeasonReportGoldenSupport.sqlReport(harness, apiDb, season);
        JsonNode response = SeasonReportGoldenSupport.apiReport(mvc, season);
        assertThat(SeasonReportGoldenSupport.differences(java, response)).as("응답과 정의 결과에서 다른 열").isEmpty();
        SeasonReportGoldenSupport.assertResponseFormat(response);

        // M4: 역할 수행이 실제로 판정된다
        System.out.println("[M4] 경로 " + PATH + " 시드 " + SEED + " rolePlayersJudged=" + response.get("rolePlayersJudged").asInt()
                + " rolePlayersUnjudged=" + response.get("rolePlayersUnjudged").asInt() + " roleFit 있음=" + !response.get("roleFit").isNull());
        assertThat(response.get("rolePlayersJudged").asInt()).as("판정된 선수").isPositive();
        assertThat(response.get("roleFit").isNull()).as("roleFit 값").isFalse();

        // 시나리오가 실제로 일어났다
        assertThat(count("SELECT count(*) AS n FROM orders WHERE season_id = :sid AND turn_no = 0 AND side = 'SELL'")).as("0턴 매도").isEqualTo(1L);
        assertThat(response.get("formationChanges").asInt()).isEqualTo(3);
        assertThat(response.get("changesWithoutOrders").asInt()).as("주문 없는 변경").isEqualTo(2);
        assertThat(response.get("formationChangeOrders").asInt()).isEqualTo(1);
        assertThat(response.get("reportTurns").asInt()).isEqualTo(PATH);

        // 0턴 수수료·대금 분리(P0-36 Q1): 원천 조회와 독립한 합으로 확인
        BigDecimal teamFee = sum("SELECT coalesce(sum(e.fee), 0) AS v FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = :sid AND o.turn_no = 0");
        BigDecimal totalFee = sum("SELECT coalesce(sum(e.fee), 0) AS v FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = :sid AND o.turn_no < " + PATH);
        BigDecimal notional = sum("SELECT coalesce(sum(e.fill_price * e.quantity), 0) AS v FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = :sid AND o.turn_no >= 1 AND o.turn_no < " + PATH);
        BigDecimal notionalWithTeam = sum("SELECT coalesce(sum(e.fill_price * e.quantity), 0) AS v FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = :sid AND o.turn_no < " + PATH);
        assertThat(new BigDecimal(response.get("teamFee").asString())).as("0턴 수수료").isEqualByComparingTo(teamFee);
        assertThat(teamFee.signum()).isPositive();
        assertThat(new BigDecimal(response.get("totalFee").asString())).isEqualByComparingTo(totalFee);
        assertThat(totalFee).as("0턴 이후 수수료가 더해진다").isGreaterThan(teamFee);
        assertThat(new BigDecimal(response.get("tradeNotional").asString())).as("대금은 1턴 이후만").isEqualByComparingTo(notional);
        assertThat(notionalWithTeam).as("0턴 대금이 따로 있다").isGreaterThan(notional);

        // M1: 독려 출발점 덮어쓰기 — 독려 세 집계(platformNudgeFee·nudgeShare·nudgeSameTurnShare)를 양수로 만들고 37열을 다시 대조한다
        long feed3 = insertNudgeFeed(3);
        long feed8 = insertNudgeFeed(8);
        long feed9 = insertNudgeFeed(9);
        setSource(0, "BUY", assets[1], "RANKING", null); // 0턴은 제외 검사
        setSource(3, "BUY", assets[5], "FEED", feed3); // 같은 턴 피드
        setSource(3, "BUY", assets[0], "RANKING", null); // 둘째 변경에 묶인 주문, 랭킹은 같은 턴으로 센다
        setSource(9, "BUY", assets[6], "FEED", feed8); // 지난 턴 피드: 독려지만 같은 턴이 아니다
        setSource(9, "SELL", assets[6], "ALERT", feed9); // 같은 턴 알림: 플랫폼 몫
        RowSet nudged = SeasonReportGoldenSupport.sqlReport(harness, apiDb, season);
        JsonNode nudgedResponse = SeasonReportGoldenSupport.apiReport(mvc, season);
        assertThat(SeasonReportGoldenSupport.differences(nudged, nudgedResponse)).as("독려 출발점 덮어쓴 뒤 응답과 정의 결과에서 다른 열").isEmpty();
        SeasonReportGoldenSupport.assertResponseFormat(nudgedResponse);
        BigDecimal nudgeShare = new BigDecimal(nudgedResponse.get("nudgeShare").asString());
        BigDecimal sameTurnShare = new BigDecimal(nudgedResponse.get("nudgeSameTurnShare").asString());
        assertThat(nudgeShare).as("독려 비중").isGreaterThan(sameTurnShare);
        assertThat(sameTurnShare.signum()).as("같은 턴 독려 비중").isPositive();
        BigDecimal platformFee = sum("SELECT coalesce(sum(e.fee), 0) AS v FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = :sid AND o.turn_no >= 1 AND o.turn_no < " + PATH + " AND o.source IN ('RANKING','ALERT')");
        assertThat(platformFee.signum()).as("플랫폼 독려 수수료").isPositive();
        assertThat(new BigDecimal(nudgedResponse.get("platformNudgeFee").asString())).as("플랫폼 독려 수수료 = 독립 합").isEqualByComparingTo(platformFee);
    }
}
