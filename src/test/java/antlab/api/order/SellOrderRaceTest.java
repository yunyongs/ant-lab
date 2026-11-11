package antlab.api.order;

import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 매도의 경합 — "쥔 트랜잭션 + 다른 스레드 요청" 구조(요청이 실제로 잠금을 기다리는지 확인한 뒤 풀어 준다).
 * 시즌 잠금이 모든 쓰기 경로의 첫 단계라 매도 ↔ 전환, 매도 ↔ 매도가 모두 직렬화된다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SellOrderRaceTest {

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

    private MockHttpServletResponse sell(long season, int turn, String ticker, int quantity) {
        return place(mvc, season, body(turn, "SELL", assetId(jdbc, ticker), quantity, null));
    }

    private long count(String table, long season) {
        if (table.equals("execution")) {
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
                long base = LockWaitSupport.waiters(jdbc); // 요청 스레드를 시작하기 전에 읽는다
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
    void 전환이_시즌을_쥐고_있을_때_온_옛_턴_매도는_기다렸다가_TURN_MISMATCH이고_아무것도_남지_않는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long orders = count("orders", season);
        long executions = count("execution", season);
        long ledgers = count("cash_ledger", season);
        String holding = jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ? AND asset_id = ?",
                String.class, season, assetId(jdbc, "000660"));

        MockHttpServletResponse late = holdWhileWaiting(() -> turnService.advance(season, 0), () -> sell(season, 0, "000660", 1));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("TURN_MISMATCH");
        assertThat(count("orders", season)).isEqualTo(orders);
        assertThat(count("execution", season)).isEqualTo(executions);
        assertThat(count("cash_ledger", season)).isEqualTo(ledgers);
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ? AND asset_id = ?", String.class, season,
                assetId(jdbc, "000660"))).isEqualTo(holding);
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(1);
        assertAllMatch(season);
    }

    @Test
    void 전량_매도가_시즌을_쥐고_있을_때_온_전환은_기다렸다가_201이고_닫힌_구간을_반영한_17개가_일치한다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);

        MockHttpServletResponse turn = holdWhileWaiting(
                () -> orderService.place(season, new PlaceOrderRequest(0, OrderSide.SELL, assetId(jdbc, "000660"), 10, null)),
                () -> advance(mvc, season, 0));

        assertThat(turn.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17L);
        JsonNode holdings = json(getHoldings(mvc, season));
        long sold = assetId(jdbc, "000660");
        holdings.get("holdings").forEach(h -> assertThat(h.get("assetId").asLong()).isNotEqualTo(sold));
        assertThat(jdbc.queryForObject("SELECT end_turn FROM lineup WHERE season_id = ? AND asset_id = ?", Integer.class, season,
                sold)).isZero();
        assertAllMatch(season);
    }

    @Test
    void 같은_종목_전량_매도_둘이_겹치면_하나는_201_하나는_422이고_원장_사슬이_맞는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM season WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, season);
                lock.executeQuery();
            }
            long base = LockWaitSupport.waiters(jdbc);
            Future<MockHttpServletResponse> first = pool.submit(() -> sell(season, 0, "069500", 100));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
            Future<MockHttpServletResponse> second = pool.submit(() -> sell(season, 0, "069500", 100));
            LockWaitSupport.awaitWaitersAbove(jdbc, base, 2);
            assertThat(first.isDone() || second.isDone()).as("잠금이 풀리기 전에는 응답이 없다").isFalse();
            holder.commit();
            responses.add(first.get(60, TimeUnit.SECONDS));
            responses.add(second.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).as("하나만 체결").containsExactlyInAnyOrder(201, 422);
        MockHttpServletResponse rejected = responses.stream().filter(r -> r.getStatus() == 422).findFirst().orElseThrow();
        assertThat(code(rejected)).isEqualTo("INSUFFICIENT_HOLDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ? AND side = 'SELL'", Long.class, season))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM cash_ledger WHERE season_id = ?", java.math.BigDecimal.class, season))
                .isEqualByComparingTo(jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?",
                        java.math.BigDecimal.class, season));
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ? AND asset_id = ?", Integer.class, season,
                assetId(jdbc, "069500"))).isZero();
        assertAllMatch(season);
    }

    @Test
    void 대사_실패로_멈춘_시즌에서도_다른_종목_매도는_받고_깨진_종목_매도는_409이며_전환은_계속_거절된다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409); // 멈춘다 — 시즌은 0턴에 그대로

        assertThat(sell(season, 0, "000660", 3).getStatus()).as("다른 종목").isEqualTo(201);
        MockHttpServletResponse broken = sell(season, 0, "069500", 1);
        assertThat(broken.getStatus()).as("깨진 종목(보유는 있고 열린 구간이 없다)").isEqualTo(409);
        assertThat(code(broken)).isEqualTo("LINEUP_MISMATCH");
        MockHttpServletResponse again = advance(mvc, season, 0);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("RECONCILIATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isZero();
    }
}
