package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.code;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static antlab.api.order.OrderApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * 거절은 6개 표의 행 수·계좌 잔액·identity 마지막 값을 하나도 바꾸지 않는다(커밋 모드 — 롤백이 실제로 일어나게).
 * 합성 고정본: 초기 자금 30,000,000원.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BuyOrderRejectTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** 요청을 보내 상태·코드를 확인하고, 거절 전후의 상태가 같음을 확인한다. */
    private JsonNode rejected(long season, String requestBody, int status, String code) throws Exception {
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, requestBody);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo(code);
        assertThat(state(jdbc, season)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo(before);
        return json(r);
    }

    // ---- 잔액 ----

    @Test
    void 잔액이_모자라면_422이고_정확히_같은_금액은_통과한다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "069500"); // 35,000원
        // 대금 + 수수료가 잔액을 넘는 수량 (857주: 29,995,000 + 4,499 = 29,999,499 → 통과 / 858주: 초과)
        rejected(season, body(0, "BUY", asset, 858, "MIDFIELDER"), 422, "INSUFFICIENT_CASH");
        // 통과 경계: 잔액을 정확히 0원으로 만드는 값 — 대금 + 수수료 == 잔액. 수수료 1원 단위로 맞추려고 합성 값을 쓴다.
        jdbc.update("UPDATE cash_account SET balance = 35005, version = version + 1 WHERE season_id = ?", season);
        jdbc.update("UPDATE cash_ledger SET balance_after = 35005 WHERE season_id = ?", season);
        // 35,000 × 1 = 35,000, 수수료 35,000 × 0.00015 = 5.25 → 5. 합계 35,005 == 잔액
        MockHttpServletResponse ok = place(mvc, season, body(0, "BUY", asset, 1, "MIDFIELDER"));
        assertThat(ok.getStatus()).isEqualTo(201);
        assertThat(json(ok).get("cashBalance").asString()).isEqualTo("0");
        // 한 푼도 없으면 한 주도 못 산다
        rejected(season, body(0, "BUY", asset, 1, null), 422, "INSUFFICIENT_CASH");
    }

    @Test
    void 수량이_int_최대여도_500이_아니라_422다() throws Exception {
        long season = startSeason(mvc);
        rejected(season, body(0, "BUY", assetId(jdbc, "SPY"), Integer.MAX_VALUE, "DEFENDER"), 422, "INSUFFICIENT_CASH");
    }

    @Test
    void 오류_본문에는_금액_가격_수량이_없다() throws Exception {
        long season = startSeason(mvc);
        JsonNode p = rejected(season, body(0, "BUY", assetId(jdbc, "SPY"), 777, "DEFENDER"), 422, "INSUFFICIENT_CASH");
        String text = p.toString();
        assertThat(text).doesNotContain("648000").doesNotContain("777").doesNotContain("30000000");
        assertThat(p.get("detail").asString()).isEqualTo("현금이 모자란다");
    }

    // ---- 턴·시즌 ----

    @Test
    void 기대_턴이_현재_턴과_다르면_409_TURN_MISMATCH다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        rejected(season, body(1, "BUY", asset, 1, "FORWARD_BREAKOUT"), 409, "TURN_MISMATCH");
        // 시즌이 1턴으로 넘어간 뒤 옛 턴(0)으로 보내도 거절
        jdbc.update("UPDATE season SET current_turn_no = 1 WHERE id = ?", season);
        rejected(season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"), 409, "TURN_MISMATCH");
    }

    @Test
    void 끝난_시즌은_409_SEASON_NOT_ACTIVE다() throws Exception {
        long season = startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        int last = jdbc.queryForObject("SELECT path_length FROM season WHERE id = ?", Integer.class, season);
        rejected(season, body(last, "BUY", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT"), 409, "SEASON_NOT_ACTIVE");
    }

    @Test
    void 우선순위_상태가_턴보다_먼저_턴이_가격보다_먼저다() throws Exception {
        long season = startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        // 끝난 시즌 + 틀린 턴 + 풀 밖 종목 + 잔액 초과 + 선언 없음 → 가장 앞 검사인 상태
        rejected(season, body(0, "BUY", 999_999_999L, Integer.MAX_VALUE, null), 409, "SEASON_NOT_ACTIVE");
    }

    @Test
    void 우선순위_가격이_잔액보다_먼저_잔액이_선언보다_먼저다() throws Exception {
        long season = startSeason(mvc);
        // 풀 밖 종목 + 잔액 초과 + 선언 없음 → ASSET_NOT_IN_POOL
        rejected(season, body(0, "BUY", 999_999_999L, Integer.MAX_VALUE, null), 422, "ASSET_NOT_IN_POOL");
        // 풀 안 종목 + 잔액 초과 + 선언 없음 → INSUFFICIENT_CASH (선언 검사보다 앞)
        rejected(season, body(0, "BUY", assetId(jdbc, "SPY"), Integer.MAX_VALUE, null), 422, "INSUFFICIENT_CASH");
    }

    @Test
    void 남의_시즌과_없는_시즌은_똑같이_404다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        long other = jdbc.queryForObject(
                "INSERT INTO member (email, name, password) VALUES ('other@example.invalid', '다른 회원', '!') RETURNING id",
                Long.class);
        long owner = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        try {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", other, season);
            JsonNode theirs = rejected(season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"), 404, "NOT_FOUND");
            JsonNode none = rejected(season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"), 404, "NOT_FOUND");
            assertThat(theirs.get("detail")).isEqualTo(none.get("detail"));
            MockHttpServletResponse missing = place(mvc, 999_999_999L, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"));
            assertThat(missing.getStatus()).isEqualTo(404);
            assertThat(json(missing).get("detail")).isEqualTo(theirs.get("detail"));
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", owner, season);
            jdbc.update("DELETE FROM member WHERE id = ?", other);
        }
    }

    @Test
    void 없는_시즌_번호는_404이고_회원을_만들지_않는다() throws Exception {
        long members = jdbc.queryForObject("SELECT count(*) FROM member", Long.class);
        MockHttpServletResponse r = place(mvc, 1L, body(0, "BUY", 1L, 1, "GOALKEEPER"));
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Long.class)).as("주문은 회원을 만들지 않는다")
                .isEqualTo(members);
    }

    // ---- 종목·선언 ----

    @Test
    void 풀_밖_종목은_422_ASSET_NOT_IN_POOL이다() throws Exception {
        long season = startSeason(mvc);
        rejected(season, body(0, "BUY", 999_999_999L, 1, "GOALKEEPER"), 422, "ASSET_NOT_IN_POOL");
        // 존재하지만 이 시즌의 가격이 없는 종목(다른 기준일의 종목 행이 없는 새 종목)
        long outsider = jdbc.queryForObject("""
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                VALUES ('KR', '777777', '가짜 풀 밖', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'),
                        DATE '2000-01-04', 'PRICE') RETURNING id""", Long.class);
        rejected(season, body(0, "BUY", outsider, 1, "GOALKEEPER"), 422, "ASSET_NOT_IN_POOL");
    }

    @Test
    void 열린_구간과_다른_선언은_422_POSITION_CONFLICT다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        assertThat(place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT")).getStatus()).isEqualTo(201);
        rejected(season, body(0, "BUY", asset, 1, "FORWARD_TARGET"), 422, "POSITION_CONFLICT");
    }

    @Test
    void 열린_구간이_없는_종목을_선언_없이_사면_422_POSITION_REQUIRED다() throws Exception {
        long season = startSeason(mvc);
        rejected(season, body(0, "BUY", assetId(jdbc, "000660"), 1, null), 422, "POSITION_REQUIRED");
    }

    // ---- 입력 검증(400) ----

    @Test
    void 수량_0_음수는_400이고_errors에_quantity가_나온다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        for (String q : new String[] {"0", "-3"}) {
            JsonNode p = rejected(season, body(0, "BUY", asset, q, "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT");
            assertThat(p.get("errors").get(0).get("field").asString()).isEqualTo("quantity");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2.0", "1e2", "null", "2147483648"})
    void 정수가_아닌_수량은_400이다(String quantity) throws Exception {
        long season = startSeason(mvc);
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", assetId(jdbc, "000660"), quantity, "FORWARD_BREAKOUT"));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
        assertThat(state(jdbc, season).get("orders")).isEqualTo(0L);
    }

    @Test
    void 따옴표_숫자_수량은_400이고_아무것도_쓰지_않는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        JsonNode p = rejected(season, body(0, "BUY", asset, "\"3\"", "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT");
        assertThat(p.toString()).doesNotContain("\"3\"");
        rejected(season, body("\"0\"", "BUY", asset, 3, "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT");
        rejected(season, body(0, "BUY", "\"" + asset + "\"", 3, "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedTurnNo\": -1, \"side\": \"BUY\", \"assetId\": 1, \"quantity\": 1}",
            "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": 0, \"quantity\": 1}",
            "{\"expectedTurnNo\": 0, \"side\": \"HOLD\", \"assetId\": 1, \"quantity\": 1}",
            "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": 1, \"quantity\": 1, \"declaredPosition\": \"KEEPER\"}"})
    void 필수_필드가_없거나_범위_밖이거나_모르는_값이면_400이다(String requestBody) throws Exception {
        long season = startSeason(mvc);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = place(mvc, season, requestBody);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 시즌_번호가_숫자가_아니면_400이다() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/abc/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "BUY", 1, 1, "GOALKEEPER")).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
    }

    // ---- 늦은 실패 ----

    @Test
    void 마지막_쓰기에서_실패하면_앞서_들어간_모든_행이_되돌아간다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        Map<String, Object> before = state(jdbc, season);
        // 라인업 INSERT 직전까지 주문·체결·보유·원장이 이미 DB 로 나간 뒤, 라인업 INSERT 만 실패하게 한다.
        jdbc.execute("""
                CREATE FUNCTION test_fail_lineup() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'test_fail_lineup'; END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_lineup BEFORE INSERT ON lineup FOR EACH ROW EXECUTE FUNCTION test_fail_lineup()");
        try {
            antlab.support.SqlRecorder.start();
            MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT"));
            java.util.List<String> sql = antlab.support.SqlRecorder.stop().stream().map(String::toLowerCase).toList();
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
            assertThat(json(r).get("detail").asString()).isEqualTo("처리 중 오류가 났다");
            assertThat(r.getContentAsString()).doesNotContain("test_fail_lineup");
            // 롤백 시험이 헛돌지 않게: 앞 표 INSERT 가 실제로 나갔다
            assertThat(sql).anyMatch(q -> q.startsWith("insert into orders"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into execution"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into holding"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into cash_ledger"));
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_lineup ON lineup");
            jdbc.execute("DROP FUNCTION test_fail_lineup()");
        }
        Map<String, Object> after = state(jdbc, season);
        // 행 수와 잔액은 그대로(identity 는 롤백돼도 소비되므로 비교 대상이 아니다)
        for (String key : new String[] {"orders", "execution", "holding", "cash_ledger", "cash_account", "lineup", "balance",
                "holding_rows", "lineup_rows", "account"}) {
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        }
    }

    @Test
    void 기존_보유_추가_매수가_늦게_실패해도_보유_값과_원장이_그대로다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        // 첫 매수로 보유와 구간을 연다. 추가 매수에는 라인업 INSERT 가 없으므로 보유 UPDATE 에서 실패시킨다
        assertThat(place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT")).getStatus()).isEqualTo(201);
        Map<String, Object> before = state(jdbc, season);
        jdbc.execute("""
                CREATE FUNCTION test_fail_holding() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'test_fail_holding'; END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_holding BEFORE UPDATE ON holding FOR EACH ROW EXECUTE FUNCTION test_fail_holding()");
        try {
            antlab.support.SqlRecorder.start();
            MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 5, null));
            java.util.List<String> sql = antlab.support.SqlRecorder.stop().stream().map(String::toLowerCase).toList();
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
            assertThat(r.getContentAsString()).doesNotContain("test_fail_holding");
            // 롤백 시험이 헛돌지 않게: 보유 UPDATE 앞의 쓰기가 실제로 나갔다
            assertThat(sql).anyMatch(q -> q.startsWith("insert into orders"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into execution"));
            assertThat(sql).anyMatch(q -> q.startsWith("insert into cash_ledger"));
            assertThat(sql).anyMatch(q -> q.startsWith("update holding"));
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_holding ON holding");
            jdbc.execute("DROP FUNCTION test_fail_holding()");
        }
        Map<String, Object> after = state(jdbc, season);
        for (String key : new String[] {"orders", "execution", "holding", "cash_ledger", "cash_account", "lineup", "balance",
                "holding_rows", "lineup_rows", "account"}) {
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        }
    }
}
