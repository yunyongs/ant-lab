package antlab.api.season;

import static antlab.api.season.AbandonSupport.abandon;
import static antlab.api.season.AbandonSupport.abandonedTurn;
import static antlab.api.season.AbandonSupport.count;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Deterministic races of MVP-14: while another connection holds the season row both requests are made to wait on the
 * lock ({@code pg_stat_activity}) before it is released, so the two really overlap. Which request wins the lock is not
 * asserted; only the outcome set is.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAbandonConcurrencyTest {

    private static final int ROUNDS = 5;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    /** Holds the season row, sends both requests in arrival order, waits until both wait on the lock, then releases. */
    private List<MockHttpServletResponse> twoBehindHolder(long season, Callable<MockHttpServletResponse> first,
            Callable<MockHttpServletResponse> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM season WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, season);
                lock.executeQuery();
            }
            long base = LockWaitSupport.waiters(jdbc);
            Future<MockHttpServletResponse> a = pool.submit(first);
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
            Future<MockHttpServletResponse> b = pool.submit(second);
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 2);
            holder.commit();
            return List.of(a.get(120, TimeUnit.SECONDS), b.get(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoParallelAbandonRequestsFinishTheSeasonOnceWithNoDuplicateTurn() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long season = startSeason(mvc);
            List<MockHttpServletResponse> r = twoBehindHolder(season, () -> abandon(mvc, season, 0),
                    () -> abandon(mvc, season, 0));
            assertThat(r.stream().map(MockHttpServletResponse::getStatus).toList()).as("round " + round)
                    .containsExactly(200, 200);
            assertThat(status(jdbc, season)).isEqualTo("FINISHED");
            assertThat(abandonedTurn(jdbc, season)).isZero();
            assertThat(count(jdbc, "turn", season)).as("one row per turn").isEqualTo(12L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season))
                    .as("17 rows per turn, no failed attempt").isEqualTo(12 * 17L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                    Long.class, season)).isZero();
        }
    }

    @Test
    void anAbandonRacingAnOrderNeverLeavesAnOrderAfterTheMark() throws Exception {
        int orderWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long season = startSeason(mvc);
            String body = "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "000660")
                    + ", \"quantity\": 1, \"declaredPosition\": \"FORWARD_BREAKOUT\"}";
            List<MockHttpServletResponse> r = twoBehindHolder(season,
                    () -> mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                            .content(body).exchange().getResponse(),
                    () -> abandon(mvc, season, 0));
            MockHttpServletResponse order = r.get(0);
            assertThat(r.get(1).getStatus()).as("round " + round).isEqualTo(200);
            if (order.getStatus() == 201) {
                orderWon++;
            } else {
                assertThat(order.getStatus()).isEqualTo(409);
                assertThat(code(order)).isEqualTo("SEASON_NOT_ACTIVE");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ? AND turn_no > ?", Long.class, season,
                    abandonedTurn(jdbc, season))).as("erd S7: no order after the abandon turn").isZero();
            assertThat(status(jdbc, season)).isEqualTo("FINISHED");
            assertThat(count(jdbc, "orders", season)).isEqualTo(order.getStatus() == 201 ? 1L : 0L);
        }
        System.out.println("[MVP-14] order won the lock in " + orderWon + "/" + ROUNDS + " rounds (info only)");
    }
}
