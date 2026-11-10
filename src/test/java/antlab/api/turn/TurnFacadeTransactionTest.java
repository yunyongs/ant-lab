package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.scoring.RuleResult;
import antlab.season.SeasonRepository;
import antlab.season.TurnFacade;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import java.lang.reflect.Method;
import java.sql.Connection;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 퍼사드의 트랜잭션 경계. ① {@code TurnFacade} 에는 {@code @Transactional} 이 없다 ② 기록 시점에 안쪽 트랜잭션은 이미 롤백됐고
 * 기록은 새 트랜잭션이다 ③ 같은 일을 {@code REQUIRES_NEW} 로 하면 바깥이 쥔 시즌 잠금을 스스로 기다린다(왜 안 쓰는가의 증거).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnFacadeTransactionTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    DataSource dataSource;
    @Autowired
    TurnService turnService;
    @Autowired
    SeasonRepository seasons;
    @MockitoSpyBean
    ReconciliationLogWriter logWriter;

    @Test
    void 퍼사드는_트랜잭션을_열지_않고_컨트롤러는_퍼사드만_쓴다() throws Exception {
        assertThat(TurnFacade.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(TurnFacade.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        for (Method m : TurnFacade.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Transactional.class)).as(m.getName()).isFalse();
        }
        Class<?> controller = Class.forName("antlab.api.turn.TurnController");
        List<Class<?>> params = new ArrayList<>(List.of(controller.getDeclaredConstructors()[0].getParameterTypes()));
        assertThat(params).containsExactly(TurnFacade.class);
    }

    @Test
    void 기록은_롤백이_끝난_뒤_새_트랜잭션에서_일어난다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        Integer turnBefore = jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season);
        List<Map<String, Object>> seenAtRecord = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            // 기록기 진입 시점. 스파이는 트랜잭션 프록시 안쪽(대상)에 걸리므로 이미 열린 트랜잭션이 있다 = 새 트랜잭션이다
            // (퍼사드에는 트랜잭션이 없었다). 안쪽 전환 트랜잭션의 흔적은 트랜잭션에 묶이지 않은 별도 연결로 본다.
            try (Connection other = dataSource.getConnection()) {
                try (var st = other.createStatement();
                        var rs = st.executeQuery("SELECT 1 FROM season WHERE id = " + season + " FOR NO KEY UPDATE NOWAIT")) {
                    assertThat(rs.next()).as("기록은 새로 열린 트랜잭션이고 안쪽 잠금은 이미 풀렸다").isTrue();
                }
                String txName = TransactionSynchronizationManager.getCurrentTransactionName();
                assertThat(txName).as("기록은 새로 열린 트랜잭션이고 안쪽 잠금은 이미 풀렸다")
                        .endsWith("antlab.scoring.ReconciliationLogWriter.record");
                seenAtRecord.add(Map.of(
                        "txActiveAtEntry", TransactionSynchronizationManager.isActualTransactionActive(),
                        "nextPriceRows", count(other, "SELECT count(*) FROM turn_price WHERE season_id = " + season + " AND turn_no = 1"),
                        "currentTurn", count(other, "SELECT current_turn_no FROM season WHERE id = " + season)));
            }
            return invocation.callRealMethod();
        }).when(logWriter).record(anyLong(), anyInt(), anyList());

        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(seenAtRecord).hasSize(1);
        Map<String, Object> seen = seenAtRecord.get(0);
        assertThat(seen.get("txActiveAtEntry")).as("기록은 새로 열린 트랜잭션 안이다").isEqualTo(true);
        assertThat(seen.get("nextPriceRows")).as("안쪽 트랜잭션의 가격 행은 이미 사라졌다").isEqualTo(0L);
        assertThat(seen.get("currentTurn")).isEqualTo(Long.valueOf(turnBefore));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season))
                .isEqualTo(17L);
    }

    private static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 17행 결과를 얻는다: 안쪽 트랜잭션이 롤백된 뒤의 예외에 실려 있다. */
    private List<RuleResult> failedResults(long season) {
        return captureResults(season);
    }

    private List<RuleResult> captureResults(long season) {
        ReconciliationFailedException[] caught = new ReconciliationFailedException[1];
        assertThatThrownBy(() -> turnService.advance(season, 0)).isInstanceOfSatisfying(ReconciliationFailedException.class,
                e -> caught[0] = e);
        return caught[0].getResults();
    }

    @Test
    void 트랜잭션_안에서_퍼사드를_부르면_아무것도_하지_않고_IllegalStateException이다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        Integer turnBefore = jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season);
        new TransactionTemplate(txManager).executeWithoutResult(s -> assertThatThrownBy(() -> facade.advance(season, 0))
                .isInstanceOf(IllegalStateException.class));
        verify(logWriter, never()).record(anyLong(), anyInt(), anyList());
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(turnBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 1", Long.class, season)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();
    }

    @Test
    void REQUIRES_NEW_로_기록하면_바깥이_쥔_시즌_잠금을_스스로_기다린다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        List<RuleResult> results = failedResults(season);
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<String> waitingQueries = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread monitor = new Thread(() -> {
            while (!stop.get()) {
                waitingQueries.addAll(jdbc.queryForList(
                        "SELECT query FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                        String.class));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        monitor.start();
        Throwable failure = null;
        try {
            outer.executeWithoutResult(status -> {
                seasons.findByIdForUpdate(season).orElseThrow(); // 바깥이 시즌 행을 쥔다
                inner.executeWithoutResult(innerStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '1s'");
                    logWriter.record(season, 1, results); // 기록기의 첫 문장이 같은 시즌 행을 잠그려 한다
                });
            });
        } catch (Throwable t) {
            failure = t;
        } finally {
            stop.set(true);
            monitor.join(2000);
        }
        System.out.println("[T4] 예외 형 = " + (failure == null ? "없음" : failure.getClass().getName()));
        assertThat(failure).as("잠금 대기 시간 초과로 실패").isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(waitingQueries).as("기다린 문장은 기록기의 시즌 잠금이다")
                .anySatisfy(q -> assertThat(q.toLowerCase()).contains("from season").contains("for no key update"));
        assertThat(waitingQueries).as("외래 키 검사(FOR KEY SHARE)는 기다린 문장이 아니다")
                .noneMatch(q -> q.toLowerCase().contains("for key share"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season)).isZero();
    }

    @Autowired
    TurnFacade facade;

    @Test
    void 실패_경로의_시즌_잠금은_두_트랜잭션에서_한_번씩이고_기록기의_잠금은_새_잠금이다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        antlab.support.SqlRecorder.start();
        assertThatThrownBy(() -> facade.advance(season, 0)).isInstanceOf(ReconciliationFailedException.class);
        List<String> sql = antlab.support.SqlRecorder.stop();
        List<String> locks = sql.stream().map(String::toLowerCase).filter(q -> q.contains("for no key update")).toList();
        System.out.println("[X10] 실패 경로 SQL " + sql.size() + "문장, 잠금 문장 " + locks.size() + "개: "
                + locks.stream().map(q -> q.contains("cash_account") ? "cash_account" : "season").toList());
        long seasonLocks = locks.stream().filter(q -> !q.contains("cash_account")).count();
        assertThat(seasonLocks).as("시즌 잠금: 전환 트랜잭션 1 + 기록 트랜잭션 1").isEqualTo(2);
        assertThat(locks.stream().filter(q -> q.contains("cash_account")).count()).as("계좌 잠금은 전환 트랜잭션에서만").isEqualTo(1);
    }
}
