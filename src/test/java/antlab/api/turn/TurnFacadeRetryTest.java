package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.season.TurnFacade;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 롤백 뒤 기록의 23505(시도 번호 유일 위반) 재시도. 잠금 아래에서는 도달하지 않는 안전망이므로 잠금을 우회한 미커밋 행으로
 * <b>실제</b> 23505 를 만든다: 다른 연결이 같은 (시즌, 턴, 시도, 코드)를 미커밋으로 넣어 두면 기록기의 INSERT 가 그 행을 기다리다가,
 * 커밋되는 순간 23505 로 실패한다. 퍼사드는 새 트랜잭션으로 다시 읽어 다음 번호로 기록한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnFacadeRetryTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    TurnFacade facade;
    @MockitoSpyBean
    ReconciliationLogWriter logWriter;

    @Test
    void 실제_23505는_새_트랜잭션으로_다시_읽어_다음_시도_번호로_기록한다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        List<String> chains = new ArrayList<>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException e) {
                chains.add(chain(e));
                throw e;
            }
        }).when(logWriter).record(anyLong(), anyInt(), anyList());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement insert = holder.prepareStatement("INSERT INTO reconciliation_log (season_id, turn_no,"
                    + " attempt_no, check_code, status, mismatch_count) VALUES (?, 1, 1, 'CASH_SUM', 'MATCH', 0)")) {
                insert.setLong(1, season);
                insert.executeUpdate(); // 미커밋 — 시즌 잠금과 무관하다(FK 검사는 FOR KEY SHARE)
            }
            Future<MockHttpServletResponse> response = pool.submit(() -> advance(mvc, season, 0));
            LockWaitSupport.awaitWaiters(jdbc, 1); // 기록기가 미커밋 유일 키를 기다린다
            assertThat(response.isDone()).isFalse();
            holder.commit();

            MockHttpServletResponse r = response.get(30, TimeUnit.SECONDS);
            assertThat(r.getStatus()).as("23505 는 응답으로 새지 않는다").isEqualTo(409);
            assertThat(r.getContentAsString()).contains("RECONCILIATION_FAILED").doesNotContain("23505")
                    .doesNotContain("uq_reconciliation_log");
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[X1] 23505 의 원인 사슬 = " + chains);
        assertThat(chains).hasSize(1); // 첫 시도만 실패했다
        assertThat(chains.get(0)).as("1순위 가지(Hibernate 제약 이름)로 알아본다")
                .contains("ConstraintViolationException[constraint=uq_reconciliation_log_season_turn_attempt_code]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 1",
                Long.class, season)).as("시도 1 은 다른 연결의 한 행뿐").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 2",
                Long.class, season)).as("시도 2 가 17행").isEqualTo(17L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no NOT IN (1, 2)",
                Long.class, season)).isZero();
        verify(logWriter, times(2)).record(anyLong(), anyInt(), anyList());
    }

    @Test
    void 다른_제약_위반은_재시도하지_않고_500이며_원래_예외가_suppressed에_남는다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        doAnswer(invocation -> {
            throw violation("23514", "new row violates check constraint \"ck_reconciliation_log_values\" 비밀값=123");
        }).when(logWriter).record(anyLong(), anyInt(), anyList());

        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("ck_reconciliation_log")
                .doesNotContain("비밀값").doesNotContain("23514");
        verify(logWriter, times(1)).record(anyLong(), anyInt(), anyList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();

        assertThatThrownBy(() -> facade.advance(season, 0)).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                        .allSatisfy(s -> assertThat(s).isInstanceOf(ReconciliationFailedException.class)));
        // 상태는 롤백된 그대로
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 1", Long.class, season))
                .isZero();
    }

    @Test
    void 세_번_연속_시도_번호가_겹치면_다시_시도하지_않고_500이다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        doAnswer(invocation -> {
            throw violation("23505", "duplicate key value violates unique constraint \"uq_reconciliation_log_season_turn_attempt_code\"");
        }).when(logWriter).record(anyLong(), anyInt(), anyList());

        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("uq_reconciliation_log");
        verify(logWriter, times(3)).record(anyLong(), anyInt(), anyList());
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isZero();
    }

    @Test
    void 전제_오류는_기록하지_않는다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        MockHttpServletResponse r = advance(mvc, season, 5); // 현재 턴은 0
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentAsString()).contains("TURN_MISMATCH");
        verify(logWriter, times(0)).record(anyLong(), anyInt(), anyList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();
    }

    private static DataIntegrityViolationException violation(String sqlState, String message) {
        return new DataIntegrityViolationException("기록 실패", new SQLException(message, sqlState));
    }

    private static String chain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName());
            if (t instanceof SQLException sql) {
                sb.append("[state=").append(sql.getSQLState()).append(']');
            }
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                sb.append("[constraint=").append(cve.getConstraintName()).append(']');
            }
            sb.append(" <- ");
        }
        return sb.toString();
    }
}
