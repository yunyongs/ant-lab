package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.agent.AgentRegistration;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
 * Committing mode, so the service transactions really commit or roll back: a rejected config leaves no season behind,
 * and two concurrent registrations of one agent make one agent. Fake rows are removed after each test.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentSeasonRollbackTest {

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

    private static StartAgentSeasonRequest request(String personaSha256) {
        return new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null,
                new AgentSeasonConfigRequest(1, personaSha256, "fake/model-1", new BigDecimal("0.7"), null, 2000,
                        60000, "p1", "r1", null, 100, new BigDecimal("1.5")));
    }

    private long registered() {
        ensureOwner(jdbc);
        return registrations.register(new RegisterAgentRequest("value-investor", ModelRoute.OPENROUTER, "fake/model-1",
                "가치 투자자")).agent().agentId();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void a_config_the_table_refuses_rolls_the_whole_season_back() {
        long agent = registered();
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        long seasonRows = count("SELECT count(*) FROM season");
        long accounts = count("SELECT count(*) FROM cash_account");
        long prices = count("SELECT count(*) FROM turn_price");
        long ledgers = count("SELECT count(*) FROM cash_ledger");

        // the validator is bypassed on purpose: only the CHECK of the table can refuse this hash
        assertThatThrownBy(() -> seasons.start(agent, request("not-a-hash"))).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT count(*) FROM season")).isEqualTo(seasonRows);
        assertThat(count("SELECT count(*) FROM cash_account")).isEqualTo(accounts);
        assertThat(count("SELECT count(*) FROM turn_price")).isEqualTo(prices);
        assertThat(count("SELECT count(*) FROM cash_ledger")).isEqualTo(ledgers);
        assertThat(count("SELECT count(*) FROM season_agent_config")).isZero();
    }

    @Test
    void a_good_config_commits_the_season_and_its_config_together() {
        long agent = registered();
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));

        long seasonId = seasons.start(agent, request("a".repeat(64))).id();

        assertThat(count("SELECT member_id FROM season WHERE id = ?", seasonId)).isEqualTo(agent);
        assertThat(count("SELECT count(*) FROM season_agent_config WHERE season_id = ?", seasonId)).isOne();
    }

    @Test
    void a_start_for_a_different_agent_than_the_acting_one_is_refused() {
        long agent = registered();
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        long seasonRows = count("SELECT count(*) FROM season");

        assertThatThrownBy(() -> seasons.start(agent + 1, request("a".repeat(64))))
                .isInstanceOfSatisfying(antlab.common.error.DomainException.class,
                        e -> assertThat(e.getCode().name()).isEqualTo("NOT_FOUND"));
        assertThat(count("SELECT count(*) FROM season")).isEqualTo(seasonRows);
    }

    @Test
    void two_concurrent_registrations_of_one_agent_make_one_agent() throws Exception {
        ensureOwner(jdbc);
        RegisterAgentRequest request = new RegisterAgentRequest("value-investor", ModelRoute.OPENROUTER,
                "fake/model-1", "가치 투자자");
        int callers = 4;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<AgentRegistration>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<AgentRegistration> call = () -> {
                    ready.countDown();
                    go.await();
                    return registrations.register(request);
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<AgentRegistration> results = new ArrayList<>();
            for (Future<AgentRegistration> future : futures) {
                results.add(future.get());
            }
            assertThat(results).extracting(r -> r.agent().agentId()).containsOnly(results.get(0).agent().agentId());
            assertThat(results).filteredOn(AgentRegistration::created).hasSize(1);
            assertThat(count("SELECT count(*) FROM agent_profile WHERE owner_member_id = ?", OWNER_ID)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }
}
