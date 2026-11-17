package antlab.projection;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
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

/** The default deployment has no engine address: the projection answers 503 ENGINE_UNAVAILABLE and stores nothing. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ProjectionEngineOffTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 엔진_주소가_없으면_현재와_시작_모두_503이고_저장하지_않는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        for (String query : new String[] {"", "?phase=START"}) {
            MockHttpServletResponse r = mvc.get().uri("/api/v1/seasons/" + season + "/projection" + query).exchange().getResponse();
            assertThat(r.getStatus()).as(query).isEqualTo(503);
            assertThat(json(r).get("code").asString()).isEqualTo("ENGINE_UNAVAILABLE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection WHERE season_id = ?", Integer.class, season)).isZero();
    }
}
