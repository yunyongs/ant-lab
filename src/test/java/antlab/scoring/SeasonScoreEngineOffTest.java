package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The default deployment has no engine address: the score answers 503 ENGINE_UNAVAILABLE, the rating 200 with the season pending. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonScoreEngineOffTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 엔진_주소가_없으면_점수는_503이고_저장하지_않으며_레이팅은_200으로_대기_시즌을_센다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }

        MockHttpServletResponse score = mvc.get().uri("/api/v1/seasons/" + season + "/score").exchange().getResponse();
        MockHttpServletResponse rating = mvc.get().uri("/api/v1/members/me/rating").exchange().getResponse();

        assertThat(score.getStatus()).isEqualTo(503);
        assertThat(code(score)).isEqualTo("ENGINE_UNAVAILABLE");
        assertThat(rating.getStatus()).isEqualTo(200);
        assertThat(json(rating).get("pendingSeasons").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history", Integer.class)).isZero();
    }
}
