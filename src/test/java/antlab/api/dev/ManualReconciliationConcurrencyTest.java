package antlab.api.dev;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.ReconciliationApiSupportAccess;
import antlab.support.DefaultBearerConfig;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 시즌에 수동 검사 둘을 동시에 보내면 기록기 잠금이 직렬화해 시도 번호 {1, 2} 가 되고 23505 가 없다.
 * 수동 검사와 매수를 동시에 보내도 둘 다 성공하고 거짓 불일치가 없다(규칙마다 한 문장이라 잠금 유무를 판별하지 못한다 —
 * 잠금은 아래 결정적 시험이 본다). 결정적 시험은 다른 연결이 시즌 행을 쥐고 있는 동안 검사가 기다렸다가, 풀린 뒤의 턴을 읽는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({PostgresTestcontainersConfig.class, TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
        "/fixtures/replay_months.sql"})
@Sql(scripts = {"/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ManualReconciliationConcurrencyTest {

    private static final int ROUNDS = 5;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;

    @Autowired
    TestMembers members;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private int attemptOf(long season) throws Exception {
        var r = mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation")
                .header("Authorization", "Bearer " + tokens.admin(members.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("attemptNo").asInt();
    }

    @Test
    void 같은_시즌_수동_검사_둘은_기록기_잠금으로_시도_1_2로_직렬화된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long season = ReconciliationApiSupportAccess.seasonWithOrders(mvc, jdbc);
            List<Integer> attempts = runTogether(List.of(() -> attemptOf(season), () -> attemptOf(season)));
            assertThat(attempts).as("라운드 " + round).containsExactlyInAnyOrder(1, 2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no IN (1, 2)",
                    Long.class, season)).isEqualTo(30);
            jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        }
    }

    @Test
    void 수동_검사와_매수가_동시에_와도_둘_다_성공하고_검사는_일관된_상태를_본다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long season = ReconciliationApiSupportAccess.seasonWithOrders(mvc, jdbc);
            List<Integer> statuses = runTogether(List.of(
                    () -> ReconciliationApiSupportAccess.buy(mvc, jdbc, season, "069500", 3, "MIDFIELDER").getStatus(),
                    () -> {
                        attemptOf(season);
                        return 200;
                    }));
            assertThat(statuses).as("라운드 " + round).containsExactly(201, 200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status = 'MISMATCH'",
                    Long.class, season)).as("동시에 돈 검사의 불일치").isZero();
            // 끝난 뒤 다시 검사해도 모두 일치한다
            attemptOf(season);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 2 AND status = 'MATCH'",
                    Long.class, season)).isEqualTo(15);
            jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        }
    }

    /**
     * 결정적 잠금 시험: 다른 연결이 시즌 행을 잠그고 있으면 검사는 첫 시즌 조회에서 기다리고, 풀린 뒤 바뀐 턴을 읽는다.
     * 잠금이 첫 시즌 조회에 없으면 턴 0 을 읽은 채 15개를 돌고 기록기에서야 기다리므로 {@code turnNo == 0} 이 되어 실패한다.
     */
    @Test
    void 시즌이_다른_연결에_잠겨_있으면_검사는_기다렸다가_바뀐_턴으로_기록한다() throws Exception {
        long season = ReconciliationApiSupportAccess.seasonWithOrders(mvc, jdbc);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM season WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, season);
                lock.executeQuery();
            }
            Future<String> response = pool.submit(() -> {
                var r = mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation")
                .header("Authorization", "Bearer " + tokens.admin(members.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange().getResponse();
                assertThat(r.getStatus()).isEqualTo(200);
                return r.getContentAsString();
            });
            // 검사 쪽 백엔드가 잠금 대기에 들어갈 때까지 기다린다(최대 10초)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                        Long.class) > 0;
                if (!waiting) {
                    Thread.sleep(50);
                }
            }
            assertThat(waiting).as("검사가 시즌 잠금을 기다린다").isTrue();
            assertThat(response.isDone()).as("잠금이 풀리기 전에는 응답이 없다").isFalse();
            try (PreparedStatement update = holder.prepareStatement("UPDATE season SET current_turn_no = 1 WHERE id = ?")) {
                update.setLong(1, season);
                update.executeUpdate();
            }
            holder.commit();

            var body = JsonMapper.shared().readTree(response.get(30, TimeUnit.SECONDS));
            assertThat(body.get("turnNo").asInt()).as("풀린 뒤의 턴을 읽는다").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1",
                    Long.class, season)).isEqualTo(15);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 0",
                    Long.class, season)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
