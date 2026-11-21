package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Same-seed sibling leagues (AG-05 D), committing mode: a sibling copies the seed and settings of its group root, points
 * only at a root, is refused when the root is closed, and takes the root's row lock so a close of the root and the
 * creation cannot interleave.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class LeagueSameSeedTest {

    private static final String MODEL = "fake/model-x";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    AgentRegistrationService registrations;
    @Autowired
    AgentSeasonService seasons;

    @BeforeEach
    void setUp() {
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
        jdbc.update("DELETE FROM persona_version WHERE persona_key = 'value-investor'");
    }

    private MockHttpServletResponse create(String body) {
        return post(mvc, BASE + "/leagues", body);
    }

    private long root(String name) throws Exception {
        MockHttpServletResponse r = create("{\"name\": \"" + name + "\"}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private MockHttpServletResponse sibling(long root, String name) {
        return create("{\"name\": \"" + name + "\", \"sameSeedAs\": " + root + "}");
    }

    private long siblingOf(long root, String name) throws Exception {
        MockHttpServletResponse r = sibling(root, name);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private void close(long league) {
        assertThat(post(mvc, BASE + "/leagues/" + league + "/close", "").getStatus()).isEqualTo(200);
    }

    private long leagueCount() {
        return jdbc.queryForObject("SELECT count(*) FROM league WHERE owner_member_id = ?", Long.class, OWNER_ID);
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asString();
    }

    @Test
    void a_sibling_plays_the_seed_and_settings_of_its_root_and_the_answer_shows_none_of_them() throws Exception {
        long root = root("Root");
        MockHttpServletResponse r = create("{\"name\": \"Sibling\", \"sameSeedAs\": " + root + ", \"maxEntries\": 4}");

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        JsonNode body = json(r);
        Set<String> keys = new TreeSet<>();
        body.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("entries", "id", "maxEntries", "name", "pathLength", "status");
        assertThat(body.get("maxEntries").asInt()).isEqualTo(4);
        assertThat(body.get("name").asString()).isEqualTo("Sibling");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        long sibling = body.get("id").asLong();

        // copy-all check: every column but the identity, the name, the pointer, the entry cap and the timestamps equals the root's
        long same = jdbc.queryForObject("""
                SELECT count(*) FROM league r JOIN league s ON s.id = ? AND r.id = ?
                WHERE s.seed = r.seed AND s.path_length = r.path_length AND s.base_date = r.base_date
                  AND s.engine_version = r.engine_version AND s.data_snapshot_date = r.data_snapshot_date
                  AND s.initial_cash = r.initial_cash AND s.fee_rate_kr = r.fee_rate_kr AND s.fee_rate_us = r.fee_rate_us
                  AND s.fx_spread_rate = r.fx_spread_rate AND s.owner_member_id = r.owner_member_id
                  AND s.seed_league_id = r.id AND r.seed_league_id IS NULL AND s.status = 'OPEN'""", Long.class, sibling, root);
        assertThat(same).isOne();
    }

    @Test
    void a_sibling_without_a_cap_takes_the_cap_of_the_root_and_a_second_sibling_joins_the_same_group() throws Exception {
        long root = root("Root");
        long first = siblingOf(root, "S1");
        long second = siblingOf(root, "S2");

        assertThat(jdbc.queryForObject("SELECT max_entries FROM league WHERE id = ?", Integer.class, first))
                .isEqualTo(jdbc.queryForObject("SELECT max_entries FROM league WHERE id = ?", Integer.class, root));
        assertThat(jdbc.queryForList("SELECT seed_league_id FROM league WHERE id IN (?, ?)", Long.class, first, second))
                .containsExactly(root, root);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT seed) FROM league WHERE id IN (?, ?, ?)", Long.class, root,
                first, second)).isOne();
    }

    @Test
    void a_sibling_cannot_be_the_target_a_group_has_one_root() throws Exception {
        long root = root("Root");
        long sibling = siblingOf(root, "S1");
        long before = leagueCount();

        MockHttpServletResponse r = sibling(sibling, "S2");

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
        assertThat(leagueCount()).isEqualTo(before);
    }

    @Test
    void a_closed_root_refuses_a_sibling_and_leaves_no_row() throws Exception {
        long root = root("Root");
        close(root);
        long before = leagueCount();

        MockHttpServletResponse r = sibling(root, "Late");

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LEAGUE_CLOSED");
        assertThat(leagueCount()).isEqualTo(before);
    }

    @Test
    void a_closed_sibling_does_not_close_the_group_while_the_root_is_open() throws Exception {
        long root = root("Root");
        close(siblingOf(root, "S1"));

        assertThat(sibling(root, "S2").getStatus()).isEqualTo(201);
    }

    @Test
    void a_missing_root_is_not_found_and_a_wrong_path_length_is_refused() throws Exception {
        long root = root("Root");
        long before = leagueCount();

        MockHttpServletResponse missing = sibling(987_654_321L, "X");
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");

        int length = jdbc.queryForObject("SELECT path_length FROM league WHERE id = ?", Integer.class, root);
        MockHttpServletResponse other = create("{\"name\": \"X\", \"sameSeedAs\": " + root + ", \"pathLength\": " + (length + 12) + "}");
        assertThat(other.getStatus()).isEqualTo(400);
        assertThat(code(other)).isEqualTo("INVALID_ARGUMENT");
        assertThat(leagueCount()).isEqualTo(before);

        assertThat(create("{\"name\": \"Y\", \"sameSeedAs\": " + root + ", \"pathLength\": " + length + "}").getStatus())
                .isEqualTo(201);
        assertThat(create("{\"name\": \"Z\", \"sameSeedAs\": 0}").getStatus()).isEqualTo(400);
    }

    @Test
    void one_agent_enters_the_root_and_a_sibling_and_plays_the_same_path_twice() throws Exception {
        long root = root("Root");
        long sibling = siblingOf(root, "S1");
        long agent = registrations.register(new RegisterAgentRequest("value-investor", ModelRoute.OPENROUTER, MODEL, "쌍둥이"))
                .agent().agentId();
        long a = enter(agent, root);
        long b = enter(agent, sibling);

        assertThat(jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, a))
                .isEqualTo(jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, b));
        long length = jdbc.queryForObject("SELECT path_length FROM season WHERE id = ?", Long.class, a);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM season_path x JOIN season_path y
                  ON y.turn_no = x.turn_no AND y.month_block_id = x.month_block_id
                WHERE x.season_id = ? AND y.season_id = ?""", Long.class, a, b)).isEqualTo(length);
    }

    private long enter(long agent, long league) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return seasons.start(agent, new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30),
                    null, league, new AgentSeasonConfigRequest(1, "a".repeat(64), "가치", MODEL, new BigDecimal("0.7"), null,
                            2000, 60000, "p1", "r1", null, 100, new BigDecimal("1.5")))).id();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ---- the root row lock ------------------------------------------------------------------------------------

    @Test
    void a_sibling_waits_for_a_close_of_the_root_that_holds_its_row_and_is_then_refused() throws Exception {
        long root = root("Root");
        long before = leagueCount();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TransactionTemplate tx = new TransactionTemplate(transactions);
            Future<?> closer = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM league WHERE id = ? FOR UPDATE", Long.class, root);
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("UPDATE league SET status = 'CLOSED', closed_at = now() WHERE id = ?", root);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MockHttpServletResponse> creating = pool.submit(() -> sibling(root, "Racer"));
            assertThatThrownBy(() -> creating.get(1, TimeUnit.SECONDS))
                    .as("the creation must wait for the row lock; without the lock it would read OPEN and insert")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            closer.get(10, TimeUnit.SECONDS);
            MockHttpServletResponse r = creating.get(10, TimeUnit.SECONDS);
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("LEAGUE_CLOSED");
            assertThat(leagueCount()).isEqualTo(before);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_close_and_a_sibling_at_the_same_moment_never_leave_a_sibling_born_after_its_root_closed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                long root = root("Root " + round);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<MockHttpServletResponse> closing = pool.submit(() -> {
                    barrier.await();
                    return post(mvc, BASE + "/leagues/" + root + "/close", "");
                });
                Future<MockHttpServletResponse> creating = pool.submit(() -> {
                    barrier.await();
                    return sibling(root, "Racer");
                });
                assertThat(closing.get().getStatus()).isEqualTo(200);
                int created = creating.get().getStatus();
                assertThat(created).isIn(201, 409);
                long late = jdbc.queryForObject("""
                        SELECT count(*) FROM league s JOIN league r ON r.id = s.seed_league_id
                        WHERE r.id = ? AND r.closed_at IS NOT NULL AND s.created_at > r.closed_at""", Long.class, root);
                assertThat(late).as("round " + round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
