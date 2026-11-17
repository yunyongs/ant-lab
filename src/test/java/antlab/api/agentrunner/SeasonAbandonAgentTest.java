package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.AgentActingAuthentication;
import antlab.season.AbandonResultDto;
import antlab.season.SeasonAbandonService;
import antlab.season.SeasonDto;
import antlab.season.SeasonQueryService;
import antlab.season.SeasonStatus;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * MVP-14 for an agent member (PLAN §6.9): the abandon service works for an agent season without any decision, and the
 * 3-turn exception never applies, so notStarted stays false even at turn 0. The runner route is AG-02 A5; this test
 * calls the service as the acting agent.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAbandonAgentTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonAbandonService abandons;
    @Autowired
    SeasonQueryService queries;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    private <T> T acting(long agent, Supplier<T> call) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anAgentSeasonAbandonedAtTurnZeroFinishesWithoutDecisionsAndIsNeverNotStarted() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody("persona-abandon", "OPENROUTER", "fake/model-1", "에이전트"));
        MockHttpServletResponse started = post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY);
        assertThat(started.getStatus()).isEqualTo(201);
        long season = json(started).get("id").asLong();
        assertThat(json(started).get("notStarted").asBoolean()).isFalse();

        AbandonResultDto result = acting(agent, () -> abandons.abandon(season, 0));

        assertThat(result.status()).isEqualTo(SeasonStatus.FINISHED);
        assertThat(result.abandonedTurnNo()).isZero();
        assertThat(result.notStarted()).as("an agent season has no 3-turn exception").isFalse();
        assertThat(result.advancedTurns()).isEqualTo(result.pathLength());
        SeasonDto read = acting(agent, () -> queries.get(season));
        assertThat(read.abandonedTurnNo()).isZero();
        assertThat(read.notStarted()).isFalse();
        var listed = acting(agent, () -> queries.list()).items();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).abandonedTurnNo()).isZero();
        assertThat(listed.get(0).notStarted()).as("list: an agent season is never not started").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_call WHERE season_id = ?", Long.class, season)).isZero();
    }
}
