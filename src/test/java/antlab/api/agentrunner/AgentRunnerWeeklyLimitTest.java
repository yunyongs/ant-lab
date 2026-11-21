package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** One agent starts a limited number of seasons in a rolling week (the limit is lowered to 2 here). */
@IntegrationTest
@Transactional
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=2")
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentRunnerWeeklyLimitTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private MockHttpServletResponse start(long agent) {
        return post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY);
    }

    @Test
    void the_season_over_the_weekly_limit_is_409_SEASON_LIMIT() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        assertThat(start(agent).getStatus()).isEqualTo(201);
        assertThat(start(agent).getStatus()).isEqualTo(201);

        MockHttpServletResponse third = start(agent);
        assertThat(third.getStatus()).isEqualTo(409);
        assertThat(json(third).get("code").asString()).isEqualTo("SEASON_LIMIT");
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id = ?", Long.class, agent)).isEqualTo(2);
    }

    @Test
    void league_seasons_count_toward_the_weekly_limit() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long first = json(post(mvc, AGENTS.replace("/agents", "/leagues"), "{\"name\": \"A\"}")).get("id").asLong();
        long second = json(post(mvc, AGENTS.replace("/agents", "/leagues"), "{\"name\": \"B\"}")).get("id").asLong();
        assertThat(post(mvc, AGENTS + "/" + agent + "/seasons", AgentRunnerSupport.startBody("fake/model-1", first, null))
                .getStatus()).isEqualTo(201);
        assertThat(post(mvc, AGENTS + "/" + agent + "/seasons", AgentRunnerSupport.startBody("fake/model-1", second, null))
                .getStatus()).isEqualTo(201);

        MockHttpServletResponse plain = start(agent);
        MockHttpServletResponse third = post(mvc, AGENTS + "/" + agent + "/seasons",
                AgentRunnerSupport.startBody("fake/model-1", first, null));

        assertThat(plain.getStatus()).isEqualTo(409);
        assertThat(json(plain).get("code").asString()).isEqualTo("SEASON_LIMIT");
        assertThat(third.getStatus()).as("the limit comes before the league's seat check").isEqualTo(409);
        assertThat(json(third).get("code").asString()).isEqualTo("SEASON_LIMIT");
    }

    @Test
    void a_season_older_than_a_week_does_not_count() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long first = json(start(agent)).get("id").asLong();
        assertThat(start(agent).getStatus()).isEqualTo(201);
        em.flush();
        jdbc.update("UPDATE season SET created_at = now() - interval '8 days', updated_at = now() - interval '8 days' "
                + "WHERE id = ?", first);
        em.clear();

        assertThat(start(agent).getStatus()).isEqualTo(201);
    }

    @Test
    void another_agent_has_its_own_weekly_count() throws Exception {
        long first = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long second = AgentRunnerSupport.register(mvc,
                AgentRunnerSupport.registerBody("momentum-trader", "OPENROUTER", "fake/model-1", "모멘텀"));
        start(first);
        start(first);
        assertThat(start(second).getStatus()).isEqualTo(201);
    }
}
