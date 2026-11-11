package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.code;
import static antlab.api.order.OrderApiSupport.fixTurn;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static antlab.api.order.OrderApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * 매도 거절은 6개 표의 행 수·값·계좌 버전·identity 마지막 값을 하나도 바꾸지 않는다(커밋 모드 — 롤백이 실제로 일어나게).
 * 합성 고정본: 000660 은 150,000원.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SellOrderRejectTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode rejected(long season, String requestBody, int status, String code) throws Exception {
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, requestBody);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo(code);
        assertThat(state(jdbc, season)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo(before);
        return json(r);
    }

    private long seasonHolding(String ticker, int quantity) throws Exception {
        long season = startSeason(mvc);
        assertThat(place(mvc, season, body(0, "BUY", assetId(jdbc, ticker), quantity, "FORWARD_BREAKOUT")).getStatus())
                .isEqualTo(201);
        return season;
    }

    // ---- T11 보유 ----

    @Test
    void 보유보다_한_주_많이_팔면_422_INSUFFICIENT_HOLDING이고_정확히_보유만큼은_통과한다() throws Exception {
        long season = seasonHolding("000660", 10);
        long asset = assetId(jdbc, "000660");
        JsonNode p = rejected(season, body(0, "SELL", asset, 11, null), 422, "INSUFFICIENT_HOLDING");
        assertThat(p.get("detail").asString()).isEqualTo("보유 수량이 모자란다");
        assertThat(place(mvc, season, body(0, "SELL", asset, 10, null)).getStatus()).isEqualTo(201);
    }

    @Test
    void 한_번도_사지_않은_종목과_전량_매도_뒤_다시_매도는_422다() throws Exception {
        long season = seasonHolding("000660", 10);
        rejected(season, body(0, "SELL", assetId(jdbc, "069500"), 1, null), 422, "INSUFFICIENT_HOLDING");
        assertThat(place(mvc, season, body(0, "SELL", assetId(jdbc, "000660"), 10, null)).getStatus()).isEqualTo(201);
        rejected(season, body(0, "SELL", assetId(jdbc, "000660"), 1, null), 422, "INSUFFICIENT_HOLDING");
    }

    @Test
    void 수량이_int_최대여도_500이_아니라_422이고_본문에_수량_가격이_없다() throws Exception {
        long season = seasonHolding("000660", 10);
        JsonNode p = rejected(season, body(0, "SELL", assetId(jdbc, "000660"), Integer.MAX_VALUE, null), 422,
                "INSUFFICIENT_HOLDING");
        assertThat(p.toString()).doesNotContain("2147483647").doesNotContain("150000").doesNotContain("10");
    }

    // ---- T12 턴·시즌 ----

    @Test
    void 지난_턴과_끝난_시즌과_없는_시즌은_매수와_같은_응답이다() throws Exception {
        long season = seasonHolding("000660", 10);
        long asset = assetId(jdbc, "000660");
        rejected(season, body(1, "SELL", asset, 1, null), 409, "TURN_MISMATCH");
        fixTurn(jdbc, season, 1, Map.of());
        rejected(season, body(0, "SELL", asset, 1, null), 409, "TURN_MISMATCH");
        assertThat(place(mvc, season, body(1, "SELL", asset, 1, null)).getStatus()).isEqualTo(201);
        MockHttpServletResponse missing = place(mvc, 999_999_999L, body(0, "SELL", asset, 1, null));
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");
        int last = jdbc.queryForObject("SELECT path_length FROM season WHERE id = ?", Integer.class, season);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        rejected(season, body(last, "SELL", asset, 1, null), 409, "SEASON_NOT_ACTIVE");
    }

    // ---- T13 선언 포지션 ----

    @Test
    void 매도에_선언_포지션을_보내면_잠금_전에_400이다() throws Exception {
        long season = seasonHolding("000660", 10);
        Map<String, Object> before = state(jdbc, season);
        SqlRecorder.start();
        MockHttpServletResponse r = place(mvc, season, body(0, "SELL", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT"));
        List<String> sql = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
        assertThat(json(r).get("detail").asString()).isEqualTo("매도에는 선언 포지션을 보내지 않는다");
        assertThat(sql).as("시즌 조회 SQL 이 한 건도 나가지 않았다").noneMatch(q -> q.toLowerCase().contains(" from season "));
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    // ---- T14 손상 상태와 늦은 실패 ----

    @Test
    void 보유가_있는데_열린_구간이_없으면_409_LINEUP_MISMATCH다() throws Exception {
        long season = seasonHolding("000660", 10);
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ?", season);
        rejected(season, body(0, "SELL", assetId(jdbc, "000660"), 1, null), 409, "LINEUP_MISMATCH");
    }

    @Test
    void 보유가_0인데_열린_구간이_남아_있으면_보유_검사가_먼저라_422다() throws Exception {
        long season = seasonHolding("000660", 10);
        jdbc.update("UPDATE holding SET quantity = 0, avg_price = 0 WHERE season_id = ?", season);
        rejected(season, body(0, "SELL", assetId(jdbc, "000660"), 1, null), 422, "INSUFFICIENT_HOLDING");
    }

    @Test
    void 원장_사슬이_깨지면_409_LEDGER_MISMATCH다() throws Exception {
        long season = seasonHolding("000660", 10);
        jdbc.update("INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (?, 'ADJUSTMENT', -1, 1)", season);
        rejected(season, body(0, "SELL", assetId(jdbc, "000660"), 1, null), 409, "LEDGER_MISMATCH");
    }

    @Test
    void 풀_밖_종목_매도는_422_ASSET_NOT_IN_POOL이고_보유_검사보다_앞이다() throws Exception {
        long season = seasonHolding("000660", 10);
        rejected(season, body(0, "SELL", 999_999_999L, 1, null), 422, "ASSET_NOT_IN_POOL");
    }

    @Test
    void 마지막_쓰기에서_실패하면_앞서_나간_주문_체결_원장_보유_변경이_모두_되돌아간다() throws Exception {
        long season = seasonHolding("000660", 10);
        long asset = assetId(jdbc, "000660");
        Map<String, Object> before = state(jdbc, season);
        // 전량 매도는 마지막에 구간을 닫는 UPDATE 를 한다 — 그 UPDATE 만 실패하게 한다.
        jdbc.execute("""
                CREATE FUNCTION test_fail_lineup_close() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'test_fail_lineup_close'; END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_lineup_close BEFORE UPDATE ON lineup FOR EACH ROW EXECUTE FUNCTION test_fail_lineup_close()");
        try {
            SqlRecorder.start();
            MockHttpServletResponse r = place(mvc, season, body(0, "SELL", asset, 10, null));
            List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
            assertThat(r.getContentAsString()).doesNotContain("test_fail_lineup_close");
            // 롤백 시험이 헛돌지 않게: 앞선 쓰기가 실제로 나갔다
            assertThat(sql).anyMatch(q -> q.startsWith("insert into orders"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into execution"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into cash_ledger"));
            assertThat(sql).anyMatch(q -> q.startsWith("update holding"));
            assertThat(sql).anyMatch(q -> q.startsWith("update lineup"));
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_lineup_close ON lineup");
            jdbc.execute("DROP FUNCTION test_fail_lineup_close()");
        }
        Map<String, Object> after = state(jdbc, season);
        for (String key : new String[] {"orders", "execution", "holding", "cash_ledger", "cash_account", "lineup", "balance",
                "holding_rows", "lineup_rows", "account"}) {
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        }
    }
}
