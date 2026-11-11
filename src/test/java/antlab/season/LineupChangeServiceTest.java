package antlab.season;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
 * 라인업 변경(내부 서비스, 공개 HTTP 경로 없음) — SQL {@code lineup_change} 와 같은 동작: 단독 변경, 같은 포지션 무변경,
 * 미보유·구간 불일치 거절, 같은 턴 여러 구간, 거절·실패 시 쓰기 없음, 전환과의 경합.
 * 팀(국내 공격·미드필더, 미국 수비·골키퍼 각 한 종목)을 사고 실제 전환으로 1턴까지 진행한 시즌에서 시작한다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class LineupChangeServiceTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LineupChangeService service;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    TurnService turnService;

    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    private String lineupRows(long season, String ticker) {
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(declared_position || ':' || start_turn || ':' || coalesce(end_turn::text, '-'), ',' ORDER BY id), '')
                FROM lineup WHERE season_id = ? AND asset_id = ?""", String.class, season, assetId(jdbc, ticker));
    }

    /** 라인업 변경이 건드리면 안 되는 표들과 시즌 행의 요약. */
    private Map<String, Object> others(long season) {
        return Map.of(
                "orders", jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season),
                "ledger", jdbc.queryForObject("SELECT count(*) FROM cash_ledger WHERE season_id = ?", Long.class, season),
                "changes", jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ?", Long.class, season),
                "holding", jdbc.queryForObject(
                        "SELECT coalesce(string_agg(asset_id || ':' || quantity || ':' || avg_price::text, ',' ORDER BY asset_id), '') FROM holding WHERE season_id = ?",
                        String.class, season),
                "account", jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                        String.class, season),
                "season", jdbc.queryForObject("SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?",
                        String.class, season));
    }

    @Test
    void 포지션을_바꾸면_열린_구간을_현재_턴에_닫고_새_구간을_열며_다른_표는_그대로다() throws Exception {
        long season = seasonAtTurnOne();
        Map<String, Object> before = others(season);

        Lineup opened = service.change(season, 1, assetId(jdbc, "069500"), Position.DEFENDER);

        assertThat(opened.getDeclaredPosition()).isEqualTo(Position.DEFENDER);
        assertThat(opened.getStartTurn()).isEqualTo(1);
        assertThat(opened.getEndTurn()).isNull();
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:1,DEFENDER:1:-");
        assertThat(others(season)).as("전술 변경 이력·주문·원장·보유·계좌·시즌은 그대로").isEqualTo(before);
    }

    @Test
    void 같은_포지션이면_기존_구간을_돌려주고_새_행이_없다() throws Exception {
        long season = seasonAtTurnOne();
        Map<String, Object> before = others(season);
        Lineup same = service.change(season, 1, assetId(jdbc, "069500"), Position.MIDFIELDER);
        assertThat(same.getStartTurn()).isZero();
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:-");
        assertThat(others(season)).isEqualTo(before);
    }

    @Test
    void 같은_턴에_두_번_바꾸면_길이_없는_구간이_남고_열린_구간은_하나뿐이다() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        service.change(season, 1, asset, Position.DEFENDER);
        service.change(season, 1, asset, Position.FORWARD_TARGET);
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:1,DEFENDER:1:1,FORWARD_TARGET:1:-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lineup WHERE season_id = ? AND asset_id = ? AND end_turn IS NULL",
                Long.class, season, asset)).isEqualTo(1L);
    }

    @Test
    void 거절은_쓰기를_남기지_않는다_미보유_구간_불일치_0턴_턴_불일치_끝난_시즌_없는_시즌_다른_회원() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long asset = assetId(jdbc, "069500");
        // 0턴 — 팀 구성 중에는 바꿀 수 없다
        assertRejected(season, ErrorCode.TURN_MISMATCH, () -> service.change(season, 0, asset, Position.DEFENDER));
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertRejected(season, ErrorCode.TURN_MISMATCH, () -> service.change(season, 0, asset, Position.DEFENDER));
        assertRejected(season, ErrorCode.TURN_MISMATCH, () -> service.change(season, 2, asset, Position.DEFENDER));
        // 미보유 — 전량 매도한 종목
        sellAll(season, 1, "069500", 100);
        assertRejected(season, ErrorCode.INSUFFICIENT_HOLDING, () -> service.change(season, 1, asset, Position.DEFENDER));
        // 보유 중인데 열린 구간이 없다
        long other = assetId(jdbc, "000660");
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ? AND asset_id = ?", season, other);
        assertRejected(season, ErrorCode.LINEUP_MISMATCH, () -> service.change(season, 1, other, Position.DEFENDER));
        jdbc.update("UPDATE lineup SET end_turn = NULL WHERE season_id = ? AND asset_id = ?", season, other);
        // 없는 시즌
        assertRejected(season, ErrorCode.NOT_FOUND, () -> service.change(999_999_999L, 1, asset, Position.DEFENDER));
        // 다른 회원의 시즌 — 없는 시즌과 같은 NOT_FOUND
        long mine = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        jdbc.update("INSERT INTO member (email, name, password) VALUES ('other@example.test', '다른 회원', 'x')");
        long stranger = jdbc.queryForObject("SELECT id FROM member WHERE email = 'other@example.test'", Long.class);
        jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", stranger, season);
        try {
            assertRejected(season, ErrorCode.NOT_FOUND, () -> service.change(season, 1, assetId(jdbc, "SPY"), Position.GOALKEEPER));
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", mine, season);
            jdbc.update("DELETE FROM member WHERE email = 'other@example.test'");
        }
        // 끝난 시즌
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        assertRejected(season, ErrorCode.SEASON_NOT_ACTIVE,
                () -> service.change(season, 1, assetId(jdbc, "SPY"), Position.GOALKEEPER));
        assertThat(lineupRows(season, "SPY")).isEqualTo("DEFENDER:0:-");
    }

    /** 거절 도우미 — 호출 직전에 시즌의 표들을 캡처하고, 호출이 그 도메인 오류로 끝난 뒤 같은지 비교한다(호출을 인자로 먼저 실행하지 않는다). */
    private void assertRejected(long season, ErrorCode expected, Runnable call) {
        Map<String, Object> before = others(season);
        String lineupBefore = allLineupRows(season);
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
        assertThat(others(season)).as("거절된 호출은 다른 표를 바꾸지 않는다").isEqualTo(before);
        assertThat(allLineupRows(season)).as("거절된 호출은 라인업을 바꾸지 않는다").isEqualTo(lineupBefore);
    }

    private String allLineupRows(long season) {
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(asset_id || ':' || declared_position || ':' || start_turn || ':' || coalesce(end_turn::text, '-'),
                                           ',' ORDER BY id), '') FROM lineup WHERE season_id = ?""", String.class, season);
    }

    @Test
    void 새_구간_삽입이_실패하면_닫은_구간도_되돌아간다() throws Exception {
        long season = seasonAtTurnOne();
        Map<String, Object> before = others(season);
        String rows = lineupRows(season, "069500");
        jdbc.execute("CREATE FUNCTION test_reject_lineup_insert() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'lineup insert rejected for test'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_lineup_insert BEFORE INSERT ON lineup FOR EACH ROW "
                + "WHEN (NEW.season_id = " + season + ") EXECUTE FUNCTION test_reject_lineup_insert()");
        try {
            assertThatThrownBy(() -> service.change(season, 1, assetId(jdbc, "069500"), Position.DEFENDER))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("lineup insert rejected for test");
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_lineup_insert ON lineup");
            jdbc.execute("DROP FUNCTION test_reject_lineup_insert()");
        }
        assertThat(lineupRows(season, "069500")).as("닫는 UPDATE 도 함께 되돌아간다").isEqualTo(rows);
        assertThat(others(season)).isEqualTo(before);
    }

    @Test
    void 전환이_시즌을_쥐고_있으면_옛_턴의_포지션_변경은_기다렸다가_409이고_변경이_먼저면_다음_턴_평가에_반영된다() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");

        // 변경이 먼저: 전환은 기다렸다가 새 구간으로 다음 턴을 평가한다 — 미드필더 보유가 없어져 2턴의 미드필더 평가액은 0 이다
        MockHttpServletResponse turn = holdWhileWaiting(() -> service.change(season, 1, asset, Position.DEFENDER),
                () -> advance(mvc, season, 1));
        assertThat(turn.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT midfielder_value FROM turn WHERE season_id = ? AND turn_no = 2",
                java.math.BigDecimal.class, season).signum()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2 AND status = 'MATCH'",
                Long.class, season)).isEqualTo(17L);

        // 전환이 먼저(2 → 3 전환이 시즌을 쥔 동안 2턴으로 온 변경): 기다렸다가 TURN_MISMATCH, 구간 그대로
        String rows = lineupRows(season, "069500");
        Object result = holdWhileWaiting(() -> turnService.advance(season, 2), () -> {
            try {
                service.change(season, 2, asset, Position.MIDFIELDER);
                return "OK";
            } catch (DomainException e) {
                return e.getCode().name();
            }
        });
        assertThat(result).isEqualTo("TURN_MISMATCH");
        assertThat(lineupRows(season, "069500")).isEqualTo(rows);
    }

    private void sellAll(long season, int turn, String ticker, int qty) {
        String b = "{\"expectedTurnNo\": " + turn + ", \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + qty + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(MediaType.APPLICATION_JSON).content(b).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
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
}
