package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonConfigRequest;
import antlab.agent.AgentSeasonService;
import antlab.agent.ModelRoute;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.StartAgentSeasonRequest;
import antlab.auth.AgentActingAuthentication;
import antlab.season.FormationDto;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Committing mode: two agents of one persona start a version nobody has recorded yet, at the same moment. The
 * per-persona advisory lock makes the second start see the first one's row and accept it. Without the lock both
 * would insert and the second would fail on the primary key.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentPersonaPinRaceTest {

    private static final String PERSONA = "race-persona";

    @Autowired
    AgentRegistrationService registrations;
    @Autowired
    AgentSeasonService seasons;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
        jdbc.update("DELETE FROM persona_version WHERE persona_key = ?", PERSONA);
    }

    private static StartAgentSeasonRequest request(String model) {
        return new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null, null,
                new AgentSeasonConfigRequest(1, "b".repeat(64), "경쟁", model, new BigDecimal("0.7"), null, 2000,
                        60000, "p1", "r1", null, 100, new BigDecimal("1.5")));
    }

    @Test
    void two_agents_starting_one_new_persona_version_at_once_record_it_once_and_both_start() throws Exception {
        ensureOwner(jdbc);
        jdbc.update("DELETE FROM persona_version WHERE persona_key = ?", PERSONA);
        List<String> models = List.of("fake/model-1", "fake/model-2");
        List<Long> agents = new ArrayList<>();
        for (String model : models) {
            agents.add(registrations.register(new RegisterAgentRequest(PERSONA, ModelRoute.OPENROUTER, model, "경쟁 " + model))
                    .agent().agentId());
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                long agent = agents.get(i);
                String model = models.get(i);
                futures.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
                    ready.countDown();
                    go.await();
                    try {
                        return seasons.start(agent, request(model)).id();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Long> future : futures) {
                try {
                    assertThat(future.get()).isPositive();
                } catch (ExecutionException e) {
                    throw new AssertionError("a start failed", e.getCause());
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM persona_version WHERE persona_key = ?", Long.class, PERSONA))
                    .isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM season_agent_config WHERE persona_key = ?", Long.class, PERSONA))
                    .isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }
}
