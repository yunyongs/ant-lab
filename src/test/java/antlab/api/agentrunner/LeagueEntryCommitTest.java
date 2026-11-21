package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonConfigRequest;
import antlab.agent.AgentSeasonService;
import antlab.agent.ModelRoute;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.StartAgentSeasonRequest;
import antlab.auth.AgentActingAuthentication;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.league.CreateLeagueRequest;
import antlab.league.LeagueService;
import antlab.season.FormationDto;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Committing mode: what a refused league entry must leave behind (nothing), and two entries of one seat at the same
 * moment. The tests of the accepted entries are in {@link LeagueEntryTest}.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class LeagueEntryCommitTest {

    @Autowired
    AgentRegistrationService registrations;
    @Autowired
    AgentSeasonService seasons;
    @Autowired
    LeagueService leagues;
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

    private static StartAgentSeasonRequest request(String model, long league) {
        return new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null, league,
                new AgentSeasonConfigRequest(1, "a".repeat(64), "가치", model, new BigDecimal("0.7"), null, 2000,
                        60000, "p1", "r1", null, 100, new BigDecimal("1.5")));
    }

    private long agent(String model) {
        return registrations.register(new RegisterAgentRequest("value-investor", ModelRoute.OPENROUTER, model, "경쟁 " + model))
                .agent().agentId();
    }

    private long league() {
        return leagues.create(new CreateLeagueRequest("Commit league", null, null, null)).id();
    }

    private long enter(long agent, String model, long league) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return seasons.start(agent, request(model, league)).id();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @ParameterizedTest
    @ValueSource(strings = {"engine_version = 'engine-that-changed'", "base_date = base_date + 1",
            "data_snapshot_date = data_snapshot_date + 1", "initial_cash = initial_cash + 1",
            "fee_rate_kr = fee_rate_kr + 0.00000001", "fee_rate_us = fee_rate_us + 0.00000001",
            "fx_spread_rate = fx_spread_rate + 0.00000001"})
    void a_league_whose_settings_no_longer_match_refuses_the_entry_and_leaves_no_row(String change) {
        ensureOwner(jdbc);
        long agent = agent("fake/model-1");
        long league = league();
        jdbc.update("UPDATE league SET " + change + " WHERE id = ?", league);

        assertThatThrownBy(() -> enter(agent, "fake/model-1", league))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LEAGUE_STALE));

        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isZero();
        assertThat(count("SELECT count(*) FROM season_agent_config WHERE member_id = ?", agent)).isZero();
        assertThat(count("SELECT count(*) FROM league_entry WHERE league_id = ?", league)).isZero();
    }

    @Test
    void an_entry_whose_path_differs_from_the_first_seat_is_refused_and_rolled_back() {
        ensureOwner(jdbc);
        long first = agent("fake/model-1");
        long second = agent("fake/model-2");
        long league = league();
        long firstSeason = enter(first, "fake/model-1", league);
        // the first seat's stored path no longer equals what the league's seed yields now (a changed engine or data)
        jdbc.update("""
                UPDATE season_path SET month_block_id = (SELECT id FROM month_block WHERE id <> season_path.month_block_id
                    ORDER BY id LIMIT 1) WHERE season_id = ? AND turn_no = 1""", firstSeason);

        assertThatThrownBy(() -> enter(second, "fake/model-2", league))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LEAGUE_STALE));

        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", second)).isZero();
        assertThat(count("SELECT count(*) FROM league_entry WHERE league_id = ?", league)).isOne();
    }

    @Test
    void a_close_and_an_entry_at_the_same_moment_never_leave_a_closed_league_with_an_unsettled_seat() throws Exception {
        ensureOwner(jdbc);
        long agent = agent("fake/model-1");
        long league = league();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> entry = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
                ready.countDown();
                go.await();
                try {
                    return seasons.start(agent, request("fake/model-1", league)).id();
                } catch (DomainException refused) {
                    return refused.getCode();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<Object> close = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return leagues.close(league).status();
                } catch (DomainException refused) {
                    return refused.getCode();
                }
            });
            ready.await();
            go.countDown();
            Object entered = entry.get();
            Object closed = close.get();
            if (entered instanceof Long) {
                assertThat(closed).isEqualTo(ErrorCode.LEAGUE_NOT_SETTLED);
            } else {
                assertThat(entered).isEqualTo(ErrorCode.LEAGUE_CLOSED);
                assertThat(closed).isNotInstanceOf(ErrorCode.class);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM league l JOIN league_entry e ON e.league_id = l.id JOIN season s ON s.id = e.season_id "
                + "WHERE l.id = ? AND l.status = 'CLOSED' AND s.status NOT IN ('FINISHED', 'VOID')", league)).isZero();
    }

    @Test
    void two_entries_of_one_seat_at_the_same_moment_give_exactly_one_seat() throws Exception {
        ensureOwner(jdbc);
        long agent = agent("fake/model-1");
        long league = league();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
                    ready.countDown();
                    go.await();
                    try {
                        return seasons.start(agent, request("fake/model-1", league)).id();
                    } catch (DomainException refused) {
                        return refused.getCode();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            assertThat(results).filteredOn(Long.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(ErrorCode.class::isInstance).containsExactly(ErrorCode.LEAGUE_SEAT_TAKEN);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM league_entry WHERE league_id = ?", league)).isOne();
        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isOne();
    }
}
