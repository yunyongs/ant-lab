package antlab.api.formation;

import static antlab.api.formation.FormationApiSupport.A;
import static antlab.api.formation.FormationApiSupport.B;
import static antlab.api.formation.FormationApiSupport.change;
import static antlab.api.formation.FormationApiSupport.changeCount;
import static antlab.api.formation.FormationApiSupport.code;
import static antlab.api.formation.FormationApiSupport.current;
import static antlab.api.formation.FormationApiSupport.json;
import static antlab.api.formation.FormationApiSupport.text;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.asset.Position;
import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import antlab.season.ChangeFormationRequest;
import antlab.season.FormationChangeService;
import antlab.season.FormationDto;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * 전술 변경 ↔ 주문 ↔ 턴 전환 경합. 한쪽을 시험이 연 트랜잭션 안에서 실제 서비스로 실행해 시즌 잠금을 쥔 채 두고, 다른 쪽 요청이
 * 잠금을 <em>실제로 기다리는지</em> 확인한 뒤 커밋한다(잠깐 자는 시간으로 순서를 주장하지 않는다). 늦은 쪽은 커밋된 최신 상태를 본다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationChangeRaceTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    TurnService turnService;
    @Autowired
    OrderService orderService;
    @Autowired
    FormationChangeService formationChangeService;
    @Autowired
    ManualReconciliationService manual;

    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    private MockHttpServletResponse buy(long season, int turn) {
        String b = "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "069500")
                + ", \"quantity\": 1}";
        return mvc.post().uri("/api/v1/seasons/" + season + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(b).exchange().getResponse();
    }

    private ChangeFormationRequest request(int turn, int[] f) {
        return new ChangeFormationRequest(turn, new FormationDto(f[0], f[1], f[2], f[3]), null);
    }

    private void assertAllMatch(long season) {
        ManualReconciliationDto result = manual.run(season);
        assertThat(result.result()).as("수동 검사").isEqualTo("MATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status = 'MISMATCH'",
                Long.class, season)).as("불일치 기록 없음").isZero();
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
    void 변경이_시즌을_쥐고_있을_때_온_주문은_기다렸다가_그_변경에_묶인다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse order = holdWhileWaiting(() -> formationChangeService.change(season, request(1, A)),
                () -> buy(season, 1));

        assertThat(order.getStatus()).isEqualTo(201);
        long change = jdbc.queryForObject("SELECT id FROM formation_change WHERE season_id = ?", Long.class, season);
        assertThat(json(order).get("formationChangeId").asLong()).isEqualTo(change);
        assertAllMatch(season);
    }

    @Test
    void 주문이_시즌을_쥐고_있을_때_온_변경은_기다렸다가_201이고_그_주문은_변경에_묶이지_않는다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse change = holdWhileWaiting(() -> orderService.place(season,
                new PlaceOrderRequest(1, OrderSide.BUY, assetId(jdbc, "069500"), 1, null)), () -> change(mvc, season, 1, A));

        assertThat(change.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT formation_change_id FROM orders WHERE season_id = ? AND turn_no = 1",
                Long.class, season)).as("변경보다 앞선 주문은 사후에 묶이지 않는다").isNull();
        assertThat(current(jdbc, season)).isEqualTo(text(A));
        assertAllMatch(season);
    }

    @Test
    void 서로_다른_두_변경이_겹치면_둘_다_기록되고_두_번째의_이전은_첫_번째의_이후다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse second = holdWhileWaiting(() -> formationChangeService.change(season, request(1, A)),
                () -> change(mvc, season, 1, B));

        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(changeCount(jdbc, season)).isEqualTo(2L);
        assertThat(text(new int[] {json(second).get("before").get("goalkeeper").asInt(),
                json(second).get("before").get("defender").asInt(), json(second).get("before").get("midfielder").asInt(),
                json(second).get("before").get("forward").asInt()})).isEqualTo(text(A));
        assertThat(current(jdbc, season)).isEqualTo(text(B));
        assertAllMatch(season);
    }

    @Test
    void 같은_포메이션으로_겹친_두_변경은_하나만_기록되고_나머지는_400이다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse second = holdWhileWaiting(() -> formationChangeService.change(season, request(1, A)),
                () -> change(mvc, season, 1, A));

        assertThat(second.getStatus()).isEqualTo(400);
        assertThat(changeCount(jdbc, season)).isEqualTo(1L);
    }

    @Test
    void 변경이_먼저면_전환의_다음_턴_목표에_새_포메이션이_반영되고_17개가_일치한다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse turn = holdWhileWaiting(() -> formationChangeService.change(season, request(1, A)),
                () -> advance(mvc, season, 1));

        assertThat(turn.getStatus()).isEqualTo(201);
        assertThat(json(turn).get("targetFormation").get("defender").asInt()).isEqualTo(A[1]);
        // 직후 조회 응답의 목표는 전환 응답과 같다
        MockHttpServletResponse get = mvc.get().uri("/api/v1/seasons/" + season + "/turns/2").exchange().getResponse();
        assertThat(json(get).get("targetFormation")).isEqualTo(json(turn).get("targetFormation"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17L);
        assertAllMatch(season);
    }

    @Test
    void 전환이_먼저면_옛_턴의_변경은_기다렸다가_409_TURN_MISMATCH이고_아무것도_남지_않는다() throws Exception {
        long season = seasonAtTurnOne();

        MockHttpServletResponse late = holdWhileWaiting(() -> turnService.advance(season, 1), () -> change(mvc, season, 1, A));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("TURN_MISMATCH");
        assertThat(changeCount(jdbc, season)).isZero();
        assertAllMatch(season);
    }

    @Test
    void 변경_주문_전환을_동시에_열_번_보내도_결과는_직렬_순서뿐이고_연결과_대사가_맞다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 10; round++) {
                long season = seasonAtTurnOne();
                CountDownLatch ready = new CountDownLatch(3);
                CountDownLatch go = new CountDownLatch(1);
                Future<MockHttpServletResponse> change = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return change(mvc, season, 1, A);
                });
                Future<MockHttpServletResponse> order = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return buy(season, 1);
                });
                Future<MockHttpServletResponse> turn = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return advance(mvc, season, 1);
                });
                ready.await();
                go.countDown();
                MockHttpServletResponse c = change.get(60, TimeUnit.SECONDS);
                MockHttpServletResponse o = order.get(60, TimeUnit.SECONDS);
                MockHttpServletResponse t = turn.get(60, TimeUnit.SECONDS);
                assertThat(t.getStatus()).as("라운드 " + round + " 전환").isEqualTo(201);
                assertThat(List.of(c.getStatus(), o.getStatus())).as("라운드 " + round).allMatch(s -> s == 201 || s == 409);
                if (c.getStatus() == 409) {
                    assertThat(code(c)).isEqualTo("TURN_MISMATCH");
                }
                if (o.getStatus() == 409) {
                    assertThat(code(o)).isEqualTo("TURN_MISMATCH");
                }
                assertThat(changeCount(jdbc, season)).isEqualTo(c.getStatus() == 201 ? 1L : 0L);
                // 주문은 자기보다 앞선 마지막 변경에만 묶인다
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM orders o WHERE o.season_id = ? AND o.turn_no = 1
                          AND o.formation_change_id IS DISTINCT FROM (SELECT max(f.id) FROM formation_change f
                              WHERE f.season_id = o.season_id AND f.turn_no = o.turn_no AND f.created_at < o.created_at)""",
                        Long.class, season)).as("라운드 " + round + " 주문 연결").isZero();
                assertAllMatch(season);
                jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
