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
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Committing mode: two starts of one agent at the same moment, one season below the weekly limit of 2. The agent's
 * member row is locked before the count, so exactly one of them passes. Without that lock both would.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=2")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentWeeklyLimitRaceTest {

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
    }

    private static StartAgentSeasonRequest request() {
        return new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null, null,
                new AgentSeasonConfigRequest(1, "a".repeat(64), "가치", "fake/model-1", new BigDecimal("0.7"), null, 2000,
                        60000, "p1", "r1", null, 100, new BigDecimal("1.5")));
    }

    @Test
    void two_concurrent_starts_below_the_weekly_limit_let_exactly_one_pass() throws Exception {
        ensureOwner(jdbc);
        long agent = registrations.register(new RegisterAgentRequest("value-investor", ModelRoute.OPENROUTER,
                "fake/model-1", "가치 투자자")).agent().agentId();
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        seasons.start(agent, request());

        int callers = 2;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    // the security context is per thread, as in a request
                    SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
                    ready.countDown();
                    go.await();
                    try {
                        seasons.start(agent, request());
                        return true;
                    } catch (DomainException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.SEASON_LIMIT);
                        return false;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            ready.await();
            go.countDown();
            int passed = 0;
            for (Future<Boolean> future : futures) {
                try {
                    if (future.get()) {
                        passed++;
                    }
                } catch (ExecutionException e) {
                    throw new AssertionError("a start failed with something other than SEASON_LIMIT", e.getCause());
                }
            }
            assertThat(passed).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id = ?", Long.class, agent))
                    .isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }
}
