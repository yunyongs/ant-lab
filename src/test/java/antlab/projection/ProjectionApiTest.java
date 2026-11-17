package antlab.projection;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.EngineProjectionAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/v1/seasons/{id}/projection with a fake engine. The synthetic pool (000660, 069500, SPY, BRK.B) and the 12-turn
 * season of the turn-advance tests; every expected number is computed here from table rows, not read from the code under test.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class ProjectionApiTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final String PROJECTIONS = "/v1/projections";
    private static final String APPLICATION_JSON = "application/json";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        ENGINE.reset();
    }

    /** A team captured from the tables: holdings in asset order, cash, total at one turn. */
    private record Team(List<String> assets, List<BigDecimal> values, BigDecimal cash) {

        BigDecimal total() {
            return values.stream().reduce(cash, BigDecimal::add);
        }

        /** Shares as the engine should be asked: down at 12 digits; cash = 1 - sum. */
        List<BigDecimal> shares() {
            return values.stream().map(v -> v.divide(total(), 12, RoundingMode.DOWN)).toList();
        }

        BigDecimal cashShare() {
            return shares().stream().reduce(BigDecimal.ONE, BigDecimal::subtract);
        }
    }

    private Team capture(long season, int turn) {
        List<String> assets = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        jdbc.query("""
                SELECT a.market || ':' || a.ticker AS key, h.quantity * tp.price AS value
                FROM holding h JOIN asset a ON a.id = h.asset_id
                JOIN turn_price tp ON tp.season_id = h.season_id AND tp.asset_id = h.asset_id AND tp.turn_no = ?
                WHERE h.season_id = ? AND h.quantity > 0 ORDER BY a.id""", rs -> {
            assets.add(rs.getString("key"));
            values.add(rs.getBigDecimal("value"));
        }, turn, season);
        BigDecimal cash = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, season);
        return new Team(assets, values, cash);
    }

    private void answer(int horizon, List<Integer> turns) {
        ENGINE.respond(PROJECTIONS, 200, APPLICATION_JSON, EngineProjectionAnswers.body(2000, horizon, turns));
    }

    private MockHttpServletResponse get(long season, String query) {
        return mvc.get().uri("/api/v1/seasons/" + season + "/projection" + query).exchange().getResponse();
    }

    private JsonNode lastRequest() throws Exception {
        List<FakeEngineServer.Recorded> all = ENGINE.requests();
        return parse(all.get(all.size() - 1).body());
    }

    private static JsonNode parse(String text) {
        return JsonMapper.shared().readTree(text);
    }

    private long season12WithTeam() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        return season;
    }

    private void finish(long season) {
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).as("turn " + t).isEqualTo(201);
        }
    }

    private static BigDecimal won(BigDecimal total, String multiple) {
        return total.multiply(new BigDecimal(multiple)).setScale(0, RoundingMode.HALF_UP);
    }

    private void assertTeamRequest(JsonNode request, Team team) {
        JsonNode portfolio = request.get("portfolios").get(0);
        assertThat(request.get("portfolios")).hasSize(1);
        assertThat(portfolio.get("key").asString()).isEqualTo("TEAM");
        assertThat(portfolio.get("cash").asString()).isEqualTo(team.cashShare().toPlainString());
        assertThat(portfolio.get("weights")).hasSize(team.assets().size());
        for (int i = 0; i < team.assets().size(); i++) {
            assertThat(portfolio.get("weights").get(i).get("asset").asString()).isEqualTo(team.assets().get(i));
            assertThat(portfolio.get("weights").get(i).get("weight").asString()).isEqualTo(team.shares().get(i).toPlainString());
        }
    }

    // ---- P3 CURRENT ----

    @Test
    void 현재_팀의_예상_분포는_현재_가격으로_엔진에_묻고_원화로_바꿔_준다() throws Exception {
        long season = season12WithTeam();
        Team team = capture(season, 0);
        answer(120, List.of(12, 60, 120));

        MockHttpServletResponse r = get(season, "");

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode request = lastRequest();
        assertThat(request.get("horizonTurns").asInt()).isEqualTo(120);
        assertThat(request.get("n").asInt()).isEqualTo(2000);
        assertThat(request.get("riskLimit").asString()).isEqualTo("-0.20000000");
        assertThat(request.get("summaryTurns")).hasSize(3);
        assertThat(request.get("seasonSeed").asString())
                .isEqualTo(jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season));
        assertThat(request.get("baseDate").asString())
                .isEqualTo(jdbc.queryForObject("SELECT base_date::text FROM season WHERE id = ?", String.class, season));
        assertTeamRequest(request, team);

        JsonNode body = json(r);
        BigDecimal total = team.total();
        assertThat(body.get("phase").asString()).isEqualTo("CURRENT");
        assertThat(body.get("turnNo").asInt()).isZero();
        assertThat(body.get("totalValue").asString()).isEqualTo(total.setScale(0).toPlainString());
        assertThat(body.get("horizonTurns").asInt()).isEqualTo(120);
        assertThat(body.get("pathCount").asInt()).isEqualTo(2000);
        assertThat(body.get("riskLimit").asString()).isEqualTo("-0.20000000");
        assertThat(body.get("bands")).hasSize(120);
        assertThat(body.get("bands").get(0).get("turn").asInt()).isEqualTo(1);
        assertThat(body.get("bands").get(0).get("p05").asString()).isEqualTo(won(total, "0.9").toPlainString());
        assertThat(body.get("bands").get(11).get("p50").asString()).isEqualTo(won(total, "1.012").toPlainString());
        assertThat(body.get("bands").get(119).get("p95").asString()).isEqualTo(won(total, "1.1").toPlainString());
        assertThat(body.get("summaries")).hasSize(3);
        JsonNode summary = body.get("summaries").get(0);
        assertThat(summary.get("turn").asInt()).isEqualTo(12);
        assertThat(summary.get("end").get("p05").asString()).isEqualTo(won(total, "0.8").toPlainString());
        assertThat(summary.get("mdd").get("p05").asString()).isEqualTo("-0.30000000");
        assertThat(summary.get("breachProbability").asString()).isEqualTo("0.12500000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isZero();
    }

    @Test
    void 전량_매도한_종목은_비중에_넣지_않는다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        long spy = assetId(jdbc, "SPY");
        String sell = "{\"expectedTurnNo\": 1, \"side\": \"SELL\", \"assetId\": " + spy + ", \"quantity\": 5}";
        assertThat(mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON).content(sell)
                .exchange().getResponse().getStatus()).isEqualTo(201);
        Team team = capture(season, 1);
        assertThat(team.assets()).doesNotContain("US:SPY");
        answer(120, List.of(12, 60, 120));

        assertThat(get(season, "?phase=CURRENT").getStatus()).isEqualTo(200);

        assertTeamRequest(lastRequest(), team);
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ? AND asset_id = ?", Integer.class, season,
                spy)).isZero();
    }

    // ---- P4 START ----

    @Test
    void 시작_예상_분포는_0턴_팀_구성_뒤의_팀으로_다시_만들고_그_뒤_매매는_섞이지_않는다() throws Exception {
        long season = season12WithTeam();
        Team atStart = capture(season, 0);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "000660", 3);
        buy(mvc, jdbc, season, 1, "BRK.B", 2);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 2, "SPY", 4);
        assertThat(capture(season, 2).cash()).isNotEqualTo(atStart.cash());
        answer(120, List.of(12, 60, 120));

        MockHttpServletResponse r = get(season, "?phase=START");

        assertThat(r.getStatus()).isEqualTo(200);
        assertTeamRequest(lastRequest(), atStart);
        JsonNode body = json(r);
        assertThat(body.get("phase").asString()).isEqualTo("START");
        assertThat(body.get("turnNo").asInt()).isZero();
        assertThat(body.get("totalValue").asString()).isEqualTo(atStart.total().setScale(0).toPlainString());
        assertThat(body.get("bands").get(0).get("p05").asString()).isEqualTo(won(atStart.total(), "0.9").toPlainString());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM projection WHERE season_id = ?", season);
        assertThat(row.get("phase")).isEqualTo("START");
        assertThat(((Number) row.get("turn_no")).intValue()).isZero();
        assertThat(((BigDecimal) row.get("total_value")).compareTo(atStart.total())).isZero();
        assertThat(((Number) row.get("horizon_turns")).intValue()).isEqualTo(120);
        assertThat(row.get("path_count")).isEqualTo(2000);
        assertThat(row.get("projection_version")).isEqualTo("m2-pj-v1");
        assertThat(row.get("engine_version")).isEqualTo("m2-sb6-v1");
        assertThat(row.get("history_digest")).isEqualTo(EngineProjectionAnswers.DIGEST);
    }

    @Test
    void 저장한_요청_문서에는_시드와_기준일이_없다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        answer(120, List.of(12, 60, 120));
        assertThat(get(season, "?phase=START").getStatus()).isEqualTo(200);

        String stored = jdbc.queryForObject("SELECT request::text FROM projection WHERE season_id = ?", String.class, season);
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
        String baseDate = jdbc.queryForObject("SELECT base_date::text FROM season WHERE id = ?", String.class, season);

        assertThat(stored).doesNotContain(seed).doesNotContain(baseDate).doesNotContain("seasonSeed").doesNotContain("baseDate");
        assertThat(stored).contains("\"TEAM\"").contains("-0.20000000");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(result) FROM projection WHERE season_id = ?", String.class, season))
                .isEqualTo("array");
    }

    // ---- P5, P6 ----

    @Test
    void 일_턴_전에는_시작_예상_분포를_볼_수_없고_엔진을_부르지_않는다() throws Exception {
        long season = season12WithTeam();

        MockHttpServletResponse r = get(season, "?phase=START");

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("TURN_NOT_REACHED");
        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void 끝나지_않은_시즌의_END와_끝난_시즌의_CURRENT는_409다() throws Exception {
        long season = season12WithTeam();
        MockHttpServletResponse notFinished = get(season, "?phase=END");
        assertThat(notFinished.getStatus()).isEqualTo(409);
        assertThat(json(notFinished).get("code").asString()).isEqualTo("SEASON_NOT_FINISHED");

        finish(season);
        MockHttpServletResponse current = get(season, "?phase=CURRENT");
        assertThat(current.getStatus()).isEqualTo(409);
        assertThat(json(current).get("code").asString()).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(get(season, "").getStatus()).as("CURRENT is the default").isEqualTo(409);
        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void 끝난_시즌의_END는_마지막_팀을_마지막_턴_가격으로_저장한다() throws Exception {
        long season = season12WithTeam();
        finish(season);
        Team last = capture(season, 12);
        answer(120, List.of(12, 60, 120));

        MockHttpServletResponse r = get(season, "?phase=END");

        assertThat(r.getStatus()).isEqualTo(200);
        assertTeamRequest(lastRequest(), last);
        JsonNode body = json(r);
        assertThat(body.get("phase").asString()).isEqualTo("END");
        assertThat(body.get("turnNo").asInt()).isEqualTo(12);
        assertThat(body.get("totalValue").asString()).isEqualTo(last.total().setScale(0).toPlainString());
        assertThat(jdbc.queryForObject("SELECT turn_no FROM projection WHERE season_id = ? AND phase = 'END'", Integer.class,
                season)).isEqualTo(12);
    }

    // ---- P7, P8 ----

    @Test
    void 한_번_저장한_시점은_엔진을_다시_부르지_않고_같은_바이트로_답한다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        answer(120, List.of(12, 60, 120));

        MockHttpServletResponse first = get(season, "?phase=START");
        MockHttpServletResponse second = get(season, "?phase=START");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(ENGINE.hits(PROJECTIONS)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isEqualTo(1);
    }

    @Test
    void 두_요청이_동시에_같은_시점을_계산해도_행은_하나고_두_응답은_같다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        answer(120, List.of(12, 60, 120));
        ENGINE.delay(Duration.ofMillis(700));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    gate.await();
                    return get(season, "?phase=START");
                }));
            }
            gate.countDown();
            MockHttpServletResponse a = calls.get(0).get();
            MockHttpServletResponse b = calls.get(1).get();

            assertThat(a.getStatus()).isEqualTo(200);
            assertThat(b.getStatus()).isEqualTo(200);
            assertThat(b.getContentAsString()).isEqualTo(a.getContentAsString());
        } finally {
            pool.shutdownNow();
        }
        assertThat(ENGINE.hits(PROJECTIONS)).as("both missed the store and asked the engine").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isEqualTo(1);
    }

    // ---- P9 horizon fallback ----

    @Test
    void 긴_예상이_422면_60턴으로_한_번_더_묻고_저장한다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        ENGINE.queue(PROJECTIONS, 422, "application/problem+json", FakeEngineServer.fixture("problem-path-too-long.json"));
        answer(60, List.of(12, 60));

        MockHttpServletResponse r = get(season, "?phase=START");

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(ENGINE.hits(PROJECTIONS)).isEqualTo(2);
        List<FakeEngineServer.Recorded> calls = ENGINE.requests();
        assertThat(parse(calls.get(0).body()).get("horizonTurns").asInt()).isEqualTo(120);
        JsonNode second = parse(calls.get(1).body());
        assertThat(second.get("horizonTurns").asInt()).isEqualTo(60);
        assertThat(second.get("summaryTurns")).hasSize(2);
        assertThat(json(r).get("horizonTurns").asInt()).isEqualTo(60);
        assertThat(json(r).get("bands")).hasSize(60);
        assertThat(jdbc.queryForObject("SELECT horizon_turns FROM projection WHERE season_id = ?", Integer.class, season))
                .isEqualTo(60);
    }

    @Test
    void 짧은_예상도_422면_422를_그대로_주고_아무것도_저장하지_않는다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        ENGINE.respond(PROJECTIONS, 422, "application/problem+json", FakeEngineServer.fixture("problem-path-too-long.json"));

        MockHttpServletResponse r = get(season, "?phase=START");

        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(json(r).get("code").asString()).isEqualTo("PATH_TOO_LONG");
        assertThat(ENGINE.hits(PROJECTIONS)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isZero();
    }

    // ---- P10 engine down ----

    @Test
    void 엔진이_바쁘면_503이고_저장하지_않으며_나중에_다시_부르면_저장한다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        ENGINE.queue(PROJECTIONS, 503, "application/problem+json", FakeEngineServer.fixture("problem-engine-busy.json"));
        answer(120, List.of(12, 60, 120));

        MockHttpServletResponse busy = get(season, "?phase=START");

        assertThat(busy.getStatus()).isEqualTo(503);
        assertThat(json(busy).get("code").asString()).isEqualTo("ENGINE_UNAVAILABLE");
        assertThat(busy.getContentAsString()).doesNotContain("Too many projections");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isZero();

        assertThat(get(season, "?phase=START").getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isEqualTo(1);
    }

    // ---- P11 ownership, input errors ----

    @Test
    void 없는_시즌과_다른_회원의_시즌은_404다() throws Exception {
        long other = jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES ('other-pj@example.invalid', 'o', 'x') RETURNING id",
                Long.class);
        long foreign = jdbc.queryForObject("""
                INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                    initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                    start_goalkeeper, start_defender, start_midfielder, start_forward,
                    current_goalkeeper, current_defender, current_midfielder, current_forward)
                VALUES (?, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                    10, 30, 40, 20, 10, 30, 40, 20) RETURNING id""", Long.class, other);
        try {
            assertThat(get(foreign, "").getStatus()).isEqualTo(404);
            assertThat(get(foreign, "?phase=START").getStatus()).isEqualTo(404);
            assertThat(get(987_654_321L, "").getStatus()).isEqualTo(404);
            assertThat(ENGINE.requests()).isEmpty();
        } finally {
            jdbc.update("DELETE FROM season WHERE id = ?", foreign);
            jdbc.update("DELETE FROM member WHERE id = ?", other);
        }
    }

    @Test
    void 알_수_없는_phase는_400이다() throws Exception {
        long season = season12WithTeam();
        MockHttpServletResponse r = get(season, "?phase=MIDDLE");
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(get(season, "?phase=current").getStatus()).isEqualTo(400);
        assertThat(ENGINE.requests()).isEmpty();
    }

    // ---- P12 exposure ----

    @Test
    void 응답에는_시드_달_버전_요약_요청이_없다() throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        answer(120, List.of(12, 60, 120));

        for (String query : List.of("", "?phase=START")) {
            MockHttpServletResponse r = get(season, query);
            assertThat(r.getStatus()).isEqualTo(200);
            String text = r.getContentAsString();
            assertThat(json(r).propertyNames()).containsExactlyInAnyOrder("phase", "turnNo", "totalValue", "horizonTurns",
                    "pathCount", "riskLimit", "bands", "summaries");
            assertThat(text).doesNotContain(EngineProjectionAnswers.DIGEST).doesNotContain("m2-sb6-v1").doesNotContain("m2-pj-v1")
                    .doesNotContain("seed").doesNotContain("Digest").doesNotContain("request");
            assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}");
        }
    }

    // ---- P15 engine answer checks ----

    @Test
    void 엔진_답이_물은_것과_다르면_500이고_저장하지_않는다(CapturedOutput output) throws Exception {
        long season = season12WithTeam();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        Map<String, String> wrongAnswers = new java.util.LinkedHashMap<>();
        wrongAnswers.put("shape", EngineProjectionAnswers.body(2000, 60, List.of(12, 60)));
        wrongAnswers.put("summary_turns", EngineProjectionAnswers.body(2000, 120, List.of(60, 120)));
        wrongAnswers.put("portfolios", EngineProjectionAnswers.body(2000, 120, List.of(12, 60, 120), "CASH"));
        wrongAnswers.put("band_count", EngineProjectionAnswers.body(2000, 120, List.of(12, 60, 120), "TEAM", 119));
        wrongAnswers.put("band_turns", EngineProjectionAnswers.body(2000, 120, List.of(12, 60, 120))
                .replace("{\"turn\":120,", "{\"turn\":121,"));
        for (Map.Entry<String, String> wrong : wrongAnswers.entrySet()) {
            ENGINE.respond(PROJECTIONS, 200, APPLICATION_JSON, wrong.getValue());

            MockHttpServletResponse r = get(season, "?phase=START");

            assertThat(r.getStatus()).as(wrong.getKey()).isEqualTo(500);
            assertThat(json(r).get("code").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(output.getAll()).as(wrong.getKey()).contains("engine.bad_response rule=" + wrong.getKey());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isZero();
        }
    }

    // ---- P16 look-ahead ----

    @Test
    void 예상_분포_코드는_시즌_경로를_읽지_않는다() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/java/antlab/projection"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file.toString()).doesNotContain("SeasonPath").doesNotContain("season_path")
                        .doesNotContain("month_block").doesNotContain("MonthBlock");
            }
        }
        assertThat(Files.readString(Path.of("src/main/java/antlab/api/season/ProjectionController.java")))
                .doesNotContain("SeasonPath");
    }

    @Test
    void 요청의_모든_값은_엔진_계약_모양이다() throws Exception {
        long season = season12WithTeam();
        answer(120, List.of(12, 60, 120));
        assertThat(get(season, "").getStatus()).isEqualTo(200);
        JsonNode request = lastRequest();

        assertThat(request.propertyNames()).containsExactlyInAnyOrder("seasonSeed", "baseDate", "horizonTurns", "n", "riskLimit",
                "summaryTurns", "portfolios");
        assertThat(ENGINE.requests().get(0).header("X-Antlab-Engine-Token")).containsExactly(TOKEN);
    }
}
