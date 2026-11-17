package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.PROBLEM;
import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.body;
import static antlab.api.season.SeasonApiSupport.counts;
import static antlab.api.season.SeasonApiSupport.formation;
import static antlab.api.season.SeasonApiSupport.get;
import static antlab.api.season.SeasonApiSupport.post;
import static antlab.support.EnginePathAnswers.body;
import static antlab.support.EnginePathAnswers.digestOf;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.SimpleBootstrap;
import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Season start with {@code antlab.engine.path-source=engine} and a fake engine. Committing mode (like the rollback test)
 * so the service transaction really rolls back; the fake rows are deleted after each test.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class SeasonStartEngineApiTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final String M2 = "m2-sb6-v1";
    private static final String PATHS = "/v1/paths";
    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final String MARKER = "MARKER-ENGINE-DETAIL-7731";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.path-source", () -> "engine");
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

    @Autowired
    JsonMapper mapper;

    @Autowired
    MonthBlockRepository repository;

    private List<ReplayMonth> replayable;
    private Map<LocalDate, Long> idByMonth;

    @BeforeEach
    void load() {
        ENGINE.reset();
        replayable = repository.findReplayMonths(SimpleBootstrap.REPLAY_START, SimpleBootstrap.replayUpperBound(BASE));
        idByMonth = new HashMap<>();
        replayable.forEach(m -> idByMonth.put(m.monthStart(), m.monthBlockId()));
    }

    private static List<LocalDate> run(String from, int count) {
        List<LocalDate> months = new ArrayList<>();
        LocalDate start = LocalDate.parse(from + "-01");
        for (int i = 0; i < count; i++) {
            months.add(start.plusMonths(i));
        }
        return months;
    }

    private static List<LocalDate> scatteredYear() {
        List<LocalDate> months = new ArrayList<>(run("2008-05", 8));
        months.addAll(run("2010-01", 4));
        return months;
    }

    private String digest() {
        return digestOf(M2, replayable, BASE);
    }

    private void answer(List<LocalDate> months) {
        ENGINE.respond(PATHS, 200, "application/json", body(M2, months, digest()));
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    private void assertRejected(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(PROBLEM.toString());
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }

    @Test
    void 엔진_달이_턴_순서대로_season_path에_저장되고_엔진_판이_season에_남는다() throws Exception {
        List<LocalDate> months = scatteredYear();
        answer(months);

        MockHttpServletResponse r = post(mvc, VALID_BODY);

        assertThat(r.getStatus()).isEqualTo(201);
        long id = json(r).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, id)).isEqualTo(M2);
        List<Long> stored = jdbc.queryForList("SELECT month_block_id FROM season_path WHERE season_id = ? ORDER BY turn_no",
                Long.class, id);
        assertThat(stored).isEqualTo(months.stream().map(idByMonth::get).toList());
    }

    @Test
    void 엔진에는_시즌의_시드와_길이와_기준일이_나간다() throws Exception {
        answer(run("2007-01", 24));

        MockHttpServletResponse r = post(mvc, body("\"-0.20\"", formation("10", "30", "30", "30"), "24"));

        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(ENGINE.requests()).hasSize(1);
        String sent = ENGINE.requests().get(0).body();
        assertThat(sent).contains("\"length\":24", "\"baseDate\":\"2026-01-02\"", "\"engineVersion\":\"m2-sb6-v1\"");
        long seed = jdbc.queryForObject("SELECT seed FROM season", Long.class);
        assertThat(sent).contains("\"seed\":\"" + seed + "\"");
    }

    @Test
    void 응답과_조회에는_엔진_판_달_해시가_없다() throws Exception {
        answer(scatteredYear());
        MockHttpServletResponse started = post(mvc, VALID_BODY);
        long id = json(started).get("id").asLong();

        List<String> texts = List.of(started.getContentAsString(), get(mvc, "/api/v1/seasons/" + id).getContentAsString(),
                get(mvc, "/api/v1/seasons").getContentAsString());

        for (String text : texts) {
            assertThat(text).doesNotContain(M2).doesNotContain(digest()).doesNotContain("2008-05").doesNotContain("engineVersion")
                    .doesNotContain("monthBlock").doesNotContain("seed");
        }
    }

    @Test
    void 엔진_달이_자바_월_블록에_없으면_500이고_아무_행도_남지_않는다() throws Exception {
        List<LocalDate> months = new ArrayList<>(run("2007-01", 12));
        months.set(3, LocalDate.of(2015, 6, 1));
        answer(months);
        Map<String, Long> before = counts(jdbc);

        MockHttpServletResponse r = post(mvc, VALID_BODY);

        assertRejected(r, 500, "INTERNAL_ERROR");
        assertThat(counts(jdbc)).isEqualTo(before);
        assertThat(r.getContentAsString()).doesNotContain("2015");
    }

    @Test
    void 개수_재사용이_틀린_경로도_500이다() throws Exception {
        Map<String, Long> before = counts(jdbc);
        for (List<LocalDate> months : List.of(run("2007-01", 11), concat(run("2007-01", 11), LocalDate.of(2007, 1, 1)))) {
            answer(months);
            assertRejected(post(mvc, VALID_BODY), 500, "INTERNAL_ERROR");
        }
        assertThat(counts(jdbc)).isEqualTo(before);
    }

    private static List<LocalDate> concat(List<LocalDate> months, LocalDate extra) {
        List<LocalDate> all = new ArrayList<>(months);
        all.add(extra);
        return all;
    }

    @Test
    void 이력_해시가_다르면_500이다(CapturedOutput output) throws Exception {
        ENGINE.respond(PATHS, 200, "application/json",
                body(M2, run("2007-01", 12), digestOf(M2, replayable.subList(1, replayable.size()), BASE)));
        Map<String, Long> before = counts(jdbc);

        assertRejected(post(mvc, VALID_BODY), 500, "INTERNAL_ERROR");

        assertThat(counts(jdbc)).isEqualTo(before);
        assertThat(output.getAll()).contains("engine.view_mismatch");
    }

    @Test
    void 엔진의_422는_422_PATH_TOO_LONG이고_행이_없다() throws Exception {
        ENGINE.respondFixture(PATHS, 422, "problem-path-too-long.json");
        Map<String, Long> before = counts(jdbc);

        assertRejected(post(mvc, VALID_BODY), 422, "PATH_TOO_LONG");

        assertThat(counts(jdbc)).isEqualTo(before);
    }

    @Test
    void 엔진이_바쁘면_503_ENGINE_UNAVAILABLE이고_다음_요청은_성공한다(CapturedOutput output) throws Exception {
        ENGINE.respond(PATHS, 503, "application/problem+json", problemWithMarker("problem-engine-busy.json"));
        Map<String, Long> before = counts(jdbc);

        MockHttpServletResponse busy = post(mvc, VALID_BODY);

        assertRejected(busy, 503, "ENGINE_UNAVAILABLE");
        assertThat(json(busy).get("detail").asString()).isEqualTo("일시적으로 처리할 수 없다. 잠시 뒤 다시 시도한다");
        assertThat(busy.getContentAsString()).doesNotContain(MARKER);
        assertThat(counts(jdbc)).isEqualTo(before);
        assertThat(output.getAll()).contains("engine.busy").doesNotContain(MARKER).doesNotContain("도메인 예외(5xx)")
                .contains("엔진을 쓸 수 없다(503)").doesNotContain("EngineUnavailableException");

        // the member lock was released with the rolled-back transaction: the same member can start right after
        answer(scatteredYear());
        assertThat(post(mvc, VALID_BODY).getStatus()).isEqualTo(201);
        assertThat(counts(jdbc).get("season")).isEqualTo(before.get("season") + 1);
    }

    @Test
    void 엔진의_이력_없음도_503이다() throws Exception {
        ENGINE.respondFixture(PATHS, 503, "problem-history-unavailable.json");

        assertRejected(post(mvc, VALID_BODY), 503, "ENGINE_UNAVAILABLE");
    }

    @Test
    void 엔진_내부_오류는_500이고_엔진_문장이_나가지_않는다() throws Exception {
        ENGINE.respond(PATHS, 500, "application/problem+json", problemWithMarker("problem-internal-error.json"));

        MockHttpServletResponse r = post(mvc, VALID_BODY);

        assertRejected(r, 500, "INTERNAL_ERROR");
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
    }

    @Test
    void 풀_점검이_먼저라_풀이_비면_엔진을_부르지_않는다() throws Exception {
        jdbc.update("DELETE FROM asset_position WHERE base_date = ?", BASE);
        answer(scatteredYear());

        assertRejected(post(mvc, VALID_BODY), 409, "POOL_NOT_LOADED");

        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void 길이_60_초과는_엔진을_부르지_않고_422다() throws Exception {
        assertRejected(post(mvc, body("\"-0.20\"", formation("10", "30", "30", "30"), "61")), 422, "PATH_TOO_LONG");

        assertThat(ENGINE.requests()).isEmpty();
    }

    private static String problemWithMarker(String fixture) {
        return FakeEngineServer.fixture(fixture).replaceFirst("\"detail\":\"[^\"]*\"", "\"detail\":\"" + MARKER + "\"");
    }
}
