package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 두 스레드가 각자는 살 수 있지만 합치면 잔액을 넘는 매수를 동시에 보낸다. 시즌 행 락이 둘을 직렬화하므로
 * 늦게 잠금을 얻은 쪽은 커밋된 최신 잔액을 다시 읽고 422 를 받는다(P0-15 두 세션 실습의 Java 판).
 * 합성 고정본: 069500 은 35,000원, 500주 = 17,500,000원 + 수수료 2,625원, 초기 자금 30,000,000원.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BuyOrderConcurrencyTest {

    private static final int ROUNDS = 10;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 동시에_둘이_사면_하나는_201_하나는_422이고_원장이_맞는다() throws Exception {
        long asset = assetId(jdbc, "069500");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long season = startSeason(mvc);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return place(mvc, season, body(0, "BUY", asset, 500, "MIDFIELDER")).getStatus();
                    }));
                }
                ready.await();
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) {
                    statuses.add(f.get(60, TimeUnit.SECONDS));
                }
                assertThat(statuses).as("라운드 " + round).containsExactlyInAnyOrder(201, 422);

                BigDecimal balance = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?",
                        BigDecimal.class, season);
                assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO).isEqualByComparingTo("12497375");
                assertLedgerChain(season, balance);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ?", Integer.class, season)).isEqualTo(500);
                // 다음 라운드가 진행 중 상한(3개)에 걸리지 않게 끝난 시즌으로 닫는다
                jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 원장 합 = 잔액, 각 행의 거래 후 잔액 = 앞 행의 잔액 + 금액 (대사 규칙 2·3 의 Java 판). */
    private void assertLedgerChain(long season, BigDecimal balance) {
        BigDecimal sum = jdbc.queryForObject("SELECT sum(amount) FROM cash_ledger WHERE season_id = ?", BigDecimal.class, season);
        assertThat(sum).isEqualByComparingTo(balance);
        BigDecimal running = BigDecimal.ZERO;
        for (var row : jdbc.queryForList("SELECT amount, balance_after FROM cash_ledger WHERE season_id = ? ORDER BY id", season)) {
            running = running.add((BigDecimal) row.get("amount"));
            assertThat((BigDecimal) row.get("balance_after")).isEqualByComparingTo(running);
        }
    }
}
