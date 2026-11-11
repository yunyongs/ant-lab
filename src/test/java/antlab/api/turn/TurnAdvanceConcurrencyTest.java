package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 같은 턴 전환 둘이 겹치는 결정적 경합. 다른 연결이 시즌 행을 쥐고 있는 동안 두 요청을 보내 둘 다 잠금을 기다리는 것을
 * {@code pg_stat_activity} 로 확인한 뒤 풀어 준다(래치는 "동시에 시작"만 보장한다). 먼저 잠금을 얻는 쪽은 단언하지 않고
 * 결과 집합만 본다 — 행 잠금 대기열의 순서는 보장되지 않는다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceConcurrencyTest {

    private static final int ROUNDS = 5;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    /** 시즌 행을 쥔 채 같은 요청 둘을 도착 순서대로 보내고, 둘 다 기다리는 것을 확인한 뒤 풀어 준다. 도착 순서대로의 응답. */
    private List<MockHttpServletResponse> twoAdvancesBehindHolder(long season, int expectedTurnNo) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM season WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, season);
                lock.executeQuery();
            }
            long base = LockWaitSupport.waiters(jdbc);
            Future<MockHttpServletResponse> first = pool.submit(() -> advance(mvc, season, expectedTurnNo));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
            Future<MockHttpServletResponse> second = pool.submit(() -> advance(mvc, season, expectedTurnNo));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 2);
            assertThat(first.isDone() || second.isDone()).as("잠금이 풀리기 전에는 응답이 없다").isFalse();
            holder.commit();
            List<MockHttpServletResponse> out = new ArrayList<>();
            out.add(first.get(60, TimeUnit.SECONDS));
            out.add(second.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 같은_턴_전환_둘은_하나만_201이고_다른_하나는_TURN_MISMATCH이며_한_벌만_생긴다() throws Exception {
        int firstArrivedWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long season = startSeason(mvc);
            buyTeam(mvc, jdbc, season);
            assertThat(advance(mvc, season, 0).getStatus()).as("중간 턴으로 가는 전환 " + round).isEqualTo(201);
            Long version = jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season);
            List<MockHttpServletResponse> r = twoAdvancesBehindHolder(season, 1);
            assertThat(r.stream().map(MockHttpServletResponse::getStatus).toList()).as("라운드 " + round)
                    .containsExactlyInAnyOrder(201, 409);
            MockHttpServletResponse loser = r.get(0).getStatus() == 409 ? r.get(0) : r.get(1);
            assertThat(code(loser)).isEqualTo("TURN_MISMATCH");
            if (r.get(0).getStatus() == 201) {
                firstArrivedWon++;
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM turn WHERE season_id = ? AND turn_no = 2", Long.class, season))
                    .isEqualTo(1L);
            Long prices = jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 2", Long.class, season);
            assertThat(prices).as("풀 전체의 가격이 한 벌").isPositive().isEqualTo(jdbc.queryForObject(
                    "SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 0", Long.class, season));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2 AND attempt_no = 1",
                    Long.class, season)).isEqualTo(17L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2",
                    Long.class, season)).isEqualTo(17L);
            assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season))
                    .as("정확히 한 번의 전환 = 버전 +1").isEqualTo(version + 1);
            assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(2);
            jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        }
        System.out.println("[X5] 먼저 도착한 요청이 이긴 라운드 = " + firstArrivedWon + "/" + ROUNDS + " (정보용, 단언 없음)");
    }

    @Test
    void 마지막_턴_전환_둘은_하나만_201이고_다른_하나는_SEASON_NOT_ACTIVE이며_시즌은_끝난다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 11; t++) {
            assertThat(advance(mvc, season, t).getStatus()).as("전환 " + t).isEqualTo(201);
        }
        Long version = jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season);
        List<MockHttpServletResponse> r = twoAdvancesBehindHolder(season, 11);
        assertThat(r.stream().map(MockHttpServletResponse::getStatus).toList()).containsExactlyInAnyOrder(201, 409);
        MockHttpServletResponse loser = r.get(0).getStatus() == 409 ? r.get(0) : r.get(1);
        assertThat(code(loser)).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn WHERE season_id = ? AND turn_no = 12", Long.class, season))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 12", Long.class,
                season)).isEqualTo(17L);
        assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, season)).isEqualTo(version + 1);
    }
}
