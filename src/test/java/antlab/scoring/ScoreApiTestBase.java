package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * Shared setup of the season score tests (MVP-18): the synthetic pool, a fake engine that answers
 * {@code POST /v1/percentiles}, and helpers that play 12-turn seasons to the end. One fake server serves every subclass,
 * so they share one Spring context. Every number a test expects is computed in the test from table rows or from the
 * canned engine counts, never read back from the code under test.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
abstract class ScoreApiTestBase {

    static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    static final String PERCENTILES = "/v1/percentiles";
    static final String APPLICATION_JSON = "application/json";
    static final int TURNS = 12;
    static final int PATHS = 2000;
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
    TestMembers members;

    @BeforeEach
    void freshEngine() {
        ENGINE.reset();
        engineAnswersMiddle();
    }

    // ---- fake engine ------------------------------------------------------------------------------------------

    void engineAnswers(String body) {
        ENGINE.respond(PERCENTILES, 200, APPLICATION_JSON, body);
    }

    void engineAnswersMiddle() {
        engineAnswers(EnginePercentileAnswers.middle(PATHS, TURNS));
    }

    void engineProblem(int status, String fixture) {
        ENGINE.respond(PERCENTILES, status, "application/problem+json", FakeEngineServer.fixture(fixture));
    }

    void engineBusy() {
        engineProblem(503, "problem-engine-busy.json");
    }

    int percentileCalls() {
        return ENGINE.hits(PERCENTILES);
    }

    JsonNode lastRequest() {
        List<FakeEngineServer.Recorded> all = ENGINE.requests();
        return tools.jackson.databind.json.JsonMapper.shared().readTree(all.get(all.size() - 1).body());
    }

    // ---- seasons -----------------------------------------------------------------------------------------------

    /** A started season with the standard 0-turn team, not yet advanced. */
    long startedSeason() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        return season;
    }

    void play(long season, int fromTurn, int toTurnExclusive) {
        for (int t = fromTurn; t < toTurnExclusive; t++) {
            assertThat(advance(mvc, season, t).getStatus()).as("turn " + t).isEqualTo(201);
        }
    }

    long finishedSeason() throws Exception {
        long season = startedSeason();
        play(season, 0, TURNS);
        return season;
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    MockHttpServletResponse getScore(long season) {
        return mvc.get().uri("/api/v1/seasons/" + season + "/score").exchange().getResponse();
    }

    MockHttpServletResponse getRating() {
        return mvc.get().uri("/api/v1/members/me/rating").exchange().getResponse();
    }

    JsonNode body(MockHttpServletResponse r) throws Exception {
        return json(r);
    }

    long memberId() {
        return members.defaultId();
    }

    Map<String, Object> scoreRow(long season) {
        return jdbc.queryForMap("SELECT * FROM season_score WHERE season_id = ?", season);
    }

    long scoreRows() {
        return jdbc.queryForObject("SELECT count(*) FROM season_score WHERE member_id = ?", Long.class, memberId());
    }

    long ratingRows() {
        return jdbc.queryForObject("SELECT count(*) FROM rating_history WHERE member_id = ?", Long.class, memberId());
    }

    /** Season ids of the member's rating rows in sequence order. */
    List<Long> ratedSeasons() {
        return jdbc.queryForList("SELECT season_id FROM rating_history WHERE member_id = ? ORDER BY seq_no", Long.class,
                memberId());
    }

    BigDecimal score(long season) {
        return jdbc.queryForObject("SELECT score FROM season_score WHERE season_id = ?", BigDecimal.class, season);
    }

    /** The seed of a season, as the engine request carries it (decimal string). */
    String seedOf(long season) {
        return jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
    }
}
