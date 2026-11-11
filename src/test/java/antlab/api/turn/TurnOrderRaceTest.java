package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.asset.Position;
import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문·수동 검사와 턴 전환의 경합. 한쪽을 시험이 연 트랜잭션 안에서 실제 서비스로 실행해 시즌 잠금을 쥔 채 두고, 다른 쪽 요청이
 * 잠금을 <em>실제로 기다리는지</em> 확인한 뒤 커밋한다. 늦은 쪽은 커밋된 최신 상태를 본다 —
 * 옛 턴 번호로 온 주문은 409 TURN_MISMATCH 이고 주문·체결·원장이 한 건도 남지 않는다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnOrderRaceTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    TurnService turnService;
    @Autowired
    OrderService orderService;
    @Autowired
    ManualReconciliationService manual;

    private MockHttpServletResponse buy(long season, int expectedTurnNo, int quantity) {
        String body = "{\"expectedTurnNo\": " + expectedTurnNo + ", \"side\": \"BUY\", \"assetId\": "
                + assetId(jdbc, "069500") + ", \"quantity\": " + quantity + "}";
        return mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    private long count(String table, long season) {
        if (table.equals("execution")) { // 체결은 주문을 거쳐 시즌에 딸린다
            return jdbc.queryForObject("SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = ?",
                    Long.class, season);
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season);
    }

    private void assertAllMatch(long season) {
        ManualReconciliationDto result = manual.run(season);
        assertThat(result.rules()).hasSize(15);
        assertThat(result.result()).as("수동 15개").isEqualTo("MATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status = 'MISMATCH'",
                Long.class, season)).as("턴 17개와 수동 15개 모두 일치").isZero();
    }

    /** 쥔 트랜잭션(시험 스레드) 안에서 holder 를 실행하고 그동안 다른 스레드의 요청이 기다리는 것을 확인한 뒤 커밋한다. */
    private <T> T holdWhileWaiting(Runnable holder, Callable<T> request) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<T>> response = new AtomicReference<>();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                holder.run();
                long base = LockWaitSupport.waiters(jdbc);
                response.set(pool.submit(request));
                try {
                    LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                assertThat(response.get().isDone()).as("잠금이 풀리기 전에는 응답이 없다").isFalse();
            });
            return response.get().get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 전환이_시즌을_쥐고_있을_때_온_옛_턴_주문은_기다렸다가_TURN_MISMATCH이고_아무것도_남지_않는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long orders = count("orders", season);
        long executions = count("execution", season);
        long ledgers = count("cash_ledger", season);
        String account = jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, season);

        MockHttpServletResponse late = holdWhileWaiting(() -> turnService.advance(season, 0), () -> buy(season, 0, 1));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("TURN_MISMATCH");
        assertThat(count("orders", season)).isEqualTo(orders);
        assertThat(count("execution", season)).isEqualTo(executions);
        assertThat(count("cash_ledger", season)).isEqualTo(ledgers);
        assertThat(jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, season)).as("계좌는 전환이 읽기만 했고 주문은 거절됐다").isEqualTo(account);
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(1);
        assertAllMatch(season);
    }

    @Test
    void 주문이_시즌을_쥐고_있을_때_온_전환은_기다렸다가_201이고_그_주문을_반영한_17개가_일치한다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long ordersBefore = count("orders", season);

        MockHttpServletResponse turn = holdWhileWaiting(
                () -> orderService.place(season, new PlaceOrderRequest(0, OrderSide.BUY, assetId(jdbc, "069500"), 3, Position.MIDFIELDER)),
                () -> advance(mvc, season, 0));

        assertThat(turn.getStatus()).isEqualTo(201);
        assertThat(count("orders", season)).isEqualTo(ordersBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17L);
        assertAllMatch(season);
    }

    @Test
    void 매수와_전환을_동시에_열_번_보내도_결과는_두_가지뿐이고_대사는_늘_맞다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long season = startSeason(mvc);
                buyTeam(mvc, jdbc, season);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<MockHttpServletResponse> order = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return buy(season, 0, 1);
                });
                Future<MockHttpServletResponse> turn = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return advance(mvc, season, 0);
                });
                ready.await();
                go.countDown();
                MockHttpServletResponse o = order.get(60, TimeUnit.SECONDS);
                MockHttpServletResponse t = turn.get(60, TimeUnit.SECONDS);
                assertThat(t.getStatus()).as("라운드 " + round + " 전환").isEqualTo(201);
                assertThat(o.getStatus()).as("라운드 " + round + " 주문").isIn(201, 409);
                if (o.getStatus() == 409) {
                    assertThat(code(o)).isEqualTo("TURN_MISMATCH");
                }
                assertAllMatch(season);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1", Long.class,
                        season)).as("턴 1 의 묶음은 전환의 17행과 수동 검사 15행").isEqualTo(32L);
                jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 깨진_시즌에서_실패한_전환_둘이_겹치면_시도_1_2가_각_17행이고_23505는_없다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM season WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, season);
                lock.executeQuery();
            }
            List<Future<MockHttpServletResponse>> responses = new ArrayList<>();
            long base = LockWaitSupport.waiters(jdbc);
            responses.add(pool.submit(() -> advance(mvc, season, 0)));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
            responses.add(pool.submit(() -> advance(mvc, season, 0)));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 2);
            holder.commit();
            for (Future<MockHttpServletResponse> f : responses) {
                MockHttpServletResponse r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getStatus()).as("500(23505 누출)이 아니다").isEqualTo(409);
                assertThat(code(r)).isEqualTo("RECONCILIATION_FAILED");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT string_agg(attempt_no || ':' || n, ',' ORDER BY attempt_no) FROM"
                + " (SELECT attempt_no, count(*) n FROM reconciliation_log WHERE season_id = ? AND turn_no = 1 GROUP BY attempt_no) x",
                String.class, season)).isEqualTo("1:17,2:17");
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isZero();
    }

    @Test
    void 전환이_시즌을_쥐고_있을_때_온_수동_검사는_기다렸다가_같은_번호_공간의_시도_2로_15행을_남긴다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);

        ManualReconciliationDto result = holdWhileWaiting(() -> turnService.advance(season, 0), () -> manual.run(season));

        assertThat(result.turnNo()).as("풀린 뒤의 턴을 읽는다").isEqualTo(1);
        assertThat(result.attemptNo()).as("전환의 17행이 시도 1").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1 AND attempt_no = 2",
                Long.class, season)).isEqualTo(15L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1 AND attempt_no = 1",
                Long.class, season)).isEqualTo(17L);
    }
}
