package antlab.leaderboard;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.SeasonScoreService;
import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Shared setup of the manager parts tests: the synthetic pool, real 12-turn seasons played through the API, and one fake
 * engine that answers the season score's {@code /v1/percentiles} and the parts call. One fake server serves every
 * subclass, so they share one Spring context. Expected values come from table rows and the contract fixtures.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
abstract class ManagerPartsTestBase {

    static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    static final String PARTS = "/v1/manager-parts";
    static final String PERCENTILES = "/v1/percentiles";
    static final int TURNS = 12;
    static final FakeEngineServer ENGINE = FakeEngineServer.start();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ENGINE::close));
    }

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonScoreService scoring;
    @Autowired
    ManagerPartsService parts;
    @Autowired
    TestMembers members;

    @BeforeEach
    void freshEngine() {
        ENGINE.reset();
        ENGINE.respond(PERCENTILES, 200, "application/json", EnginePercentileAnswers.middle(2000, TURNS));
    }

    long memberId() {
        return members.defaultId();
    }

    long finishedSeason() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < TURNS; t++) {
            assertThat(advance(mvc, season, t).getStatus()).as("turn " + t).isEqualTo(201);
        }
        return season;
    }

    /** A human season abandoned at turn 0: the remaining turns run without trades and it finishes "not started". */
    long notStartedSeason() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/abandon")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTurnNo\": 0}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        return season;
    }

    /** Scores the member's seasons, so a test can pick the parts answer that fits the stored risk item. */
    void scoreAll() {
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).isZero();
    }

    boolean hasRiskItem(long season) {
        return jdbc.queryForObject("SELECT risk_score IS NOT NULL FROM season_score WHERE season_id = ?", Boolean.class, season);
    }

    /** The 200 answer whose risk-item percentile is present exactly when the season has a risk item. */
    String goodAnswer(long season) {
        return FakeEngineServer.fixture(hasRiskItem(season) ? "manager-parts-response.json"
                : "manager-parts-response-no-risk-item.json");
    }

    void partsAnswer(int status, String body) {
        ENGINE.respond(PARTS, status, status == 200 ? "application/json" : "application/problem+json", body);
    }

    void partsAnswerOnce(int status, String body) {
        ENGINE.queue(PARTS, status, status == 200 ? "application/json" : "application/problem+json", body);
    }

    int partsCalls() {
        return ENGINE.hits(PARTS);
    }

    int scoreCalls() {
        return ENGINE.hits(PERCENTILES);
    }

    /** The next percentile call answers with this problem; later ones keep the 200 of {@link #freshEngine}. */
    void scoreAnswerOnce(int status, String problemFixture) {
        ENGINE.queue(PERCENTILES, status, "application/problem+json", FakeEngineServer.fixture(problemFixture));
    }

    long scoreRows(long season) {
        return jdbc.queryForObject("SELECT count(*) FROM season_score WHERE season_id = ?", Long.class, season);
    }

    JsonNode lastPartsRequest() {
        List<FakeEngineServer.Recorded> calls = ENGINE.requests().stream().filter(r -> r.path().equals(PARTS)).toList();
        return JsonMapper.shared().readTree(calls.get(calls.size() - 1).body());
    }

    long partsRows() {
        return jdbc.queryForObject("SELECT count(*) FROM season_manager_parts", Long.class);
    }

    Map<String, Object> partsRow(long season) {
        return jdbc.queryForMap("""
                SELECT exclusion, reference_key, reference_size, bot_version, engine_version, pct_excess_cagr::text AS pct_return,
                       pct_risk_item::text AS pct_risk_item, part_return::text AS part_return,
                       part_risk_adjusted::text AS part_risk_adjusted, part_risk::text AS part_risk, calmar_diff::text AS calmar_diff,
                       part_process::text AS part_process, process_score_version, final_multiple::text AS final_multiple
                FROM season_manager_parts WHERE season_id = ? AND reference_version = 'ref69-v1'""", season);
    }
}
