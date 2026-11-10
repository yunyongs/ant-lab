package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비관적 락(PESSIMISTIC_WRITE)과 낙관적 락(@Version). 서로 다른 트랜잭션이 필요해 커밋하는 시험이고,
 * 자기가 만든 행을 {@code SeasonAccountFixture.cleanUp()} 으로 지운다. 시간 상한을 두어 시험이 멈추지 않게 한다.
 */
@IntegrationTest
class LockingTest {

    /** 기다림·완료 확인의 시간 상한(초). 시험이 멈추지 않게 한다. */
    private static final long FINISH_LIMIT_S = 10;

    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    TransactionTemplate tx;
    SeasonAccountFixture fixture;
    ExecutorService pool;
    long seasonId;

    @BeforeEach
    void 준비() {
        tx = new TransactionTemplate(txManager);
        pool = Executors.newFixedThreadPool(2);
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        Season season = fixture.newSeason(fixture.newMember());
        fixture.newAccount(season);
        seasonId = season.getId();
    }

    @AfterEach
    void 정리() {
        pool.shutdownNow();
        fixture.cleanUp();
    }

    private <T> T inTx(Supplier<T> body) {
        return tx.execute(status -> body.get());
    }

    @Test
    void 시즌_락_SQL은_for_no_key_update를_낸다() {
        List<String> sqls = inTx(() -> {
            SqlRecorder.start();
            seasons.findByIdForUpdate(seasonId).orElseThrow();
            return SqlRecorder.stop();
        });
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).matches("(?s).* for no key update of \\w+");
    }

    @Test
    void 계좌_락_SQL도_for_no_key_update를_낸다() {
        List<String> sqls = inTx(() -> {
            SqlRecorder.start();
            accounts.findBySeasonIdForUpdate(seasonId).orElseThrow();
            return SqlRecorder.stop();
        });
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).matches("(?s).* for no key update of \\w+");
    }

    @Test
    void 잠그지_않는_조회_SQL에는_for_update가_없다() {
        List<String> sqls = inTx(() -> {
            SqlRecorder.start();
            seasons.findById(seasonId).orElseThrow();
            return SqlRecorder.stop();
        });
        assertThat(sqls).noneMatch(s -> s.contains("for no key update") || s.contains("for update"));
    }

    @Test
    void 시즌_락을_쥔_동안_같은_호출은_기다리고_커밋하면_커밋된_값을_읽는다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
            Season s = seasons.findByIdForUpdate(seasonId).orElseThrow();
            locked.countDown();
            await(release);
            s.advanceTurn();
        }));
        assertThat(locked.await(FINISH_LIMIT_S, TimeUnit.SECONDS)).as("A 가 락을 얻음").isTrue();

        Future<Integer> b = pool.submit(() -> inTx(() -> seasons.findByIdForUpdate(seasonId).orElseThrow()
                .getCurrentTurnNo()));
        awaitLockWaiter();
        assertThat(b.isDone()).as("A 가 쥔 동안 B 는 끝나지 않아야 한다").isFalse();

        release.countDown();
        a.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        assertThat(b.get(FINISH_LIMIT_S, TimeUnit.SECONDS)).as("B 는 A 가 커밋한 현재 턴을 읽는다").isEqualTo(1);
    }

    @Test
    void 계좌_락도_같다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
            var account = accounts.findBySeasonIdForUpdate(seasonId).orElseThrow();
            locked.countDown();
            await(release);
            account.applyLedgerAmount(new BigDecimal("500"));
        }));
        assertThat(locked.await(FINISH_LIMIT_S, TimeUnit.SECONDS)).isTrue();

        Future<BigDecimal> b = pool.submit(() -> inTx(() -> accounts.findBySeasonIdForUpdate(seasonId).orElseThrow()
                .getBalance()));
        awaitLockWaiter();
        assertThat(b.isDone()).as("A 가 쥔 동안 B 는 끝나지 않아야 한다").isFalse();

        release.countDown();
        a.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        assertThat(b.get(FINISH_LIMIT_S, TimeUnit.SECONDS)).isEqualByComparingTo("500");
    }

    @Test
    void 락_순서를_지키면_두_스레드가_교착_없이_차례로_끝난다() throws Exception {
        // 두 쓰기가 모두 season → cash_account 순서로 잠그면 서로 기다려도 순환하지 않는다
        Runnable work = () -> tx.executeWithoutResult(status -> {
            seasons.findByIdForUpdate(seasonId).orElseThrow();
            accounts.findBySeasonIdForUpdate(seasonId).orElseThrow().applyLedgerAmount(new BigDecimal("100"));
        });
        Future<?> a = pool.submit(work);
        Future<?> b = pool.submit(work);
        a.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        b.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class,
                seasonId)).isEqualByComparingTo("200");
    }

    @Test
    void 같은_버전을_읽은_두_트랜잭션_중_나중_커밋은_낙관적_락_예외로_실패한다() throws Exception {
        CountDownLatch bothRead = new CountDownLatch(2);

        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            Season s = seasons.findById(seasonId).orElseThrow();
            bothRead.countDown();
            await(bothRead);
            s.advanceTurn();
        }));
        Future<?> second = pool.submit(() -> tx.executeWithoutResult(status -> {
            Season s = seasons.findById(seasonId).orElseThrow();
            bothRead.countDown();
            await(bothRead);
            // 첫 트랜잭션이 커밋한 다음에 커밋한다
            awaitFuture(first);
            s.advanceTurn();
        }));

        first.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(FINISH_LIMIT_S, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, seasonId))
                .as("진 쪽의 변경은 반영되지 않는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM season WHERE id = ?", Long.class, seasonId)).isEqualTo(1L);
    }

    /** B 가 실제로 행 잠금을 기다리는 세션이 될 때까지(상한까지) 기다린다. 못 되면 시험이 실패한다. */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINISH_LIMIT_S);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("잠금을 기다리는 세션이 나타나지 않았다");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(FINISH_LIMIT_S, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitFuture(Future<?> f) {
        try {
            f.get(FINISH_LIMIT_S, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
