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

import antlab.account.OrderQuoteDto;
import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * POST /api/v1/seasons/{id}/orders/quote — the quote equals the fill placeOrder makes next with the same body, writes
 * nothing, and refuses exactly what placeOrder refuses. Synthetic fixture (not the real sample): 000660 KR 150,000,
 * SPY US 648,000, cash 30,000,000, rates KR 0.00015, US 0.0025 + FX 0.004.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class OrderQuoteApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse quote(long seasonId, String body) {
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/orders/quote").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    private JsonNode quoted(long seasonId, String body) throws Exception {
        MockHttpServletResponse r = quote(seasonId, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    /** The fields both answers carry must be equal: quote fillPrice/notional/fee/cashAfter = fill fillPrice/notional/fee/cashBalance. */
    private static void sameAsFill(JsonNode q, JsonNode fill) {
        assertThat(q.get("fillPrice")).isEqualTo(fill.get("fillPrice"));
        assertThat(q.get("notional")).isEqualTo(fill.get("notional"));
        assertThat(q.get("fee")).isEqualTo(fill.get("fee"));
        assertThat(q.get("cashAfter")).isEqualTo(fill.get("cashBalance"));
    }

    @Test
    void 국내_매수_견적은_대금_수수료_체결_뒤_현금_최대_수량을_주고_아무것도_쓰지_않는다() throws Exception {
        long season = startSeason(mvc);
        String buy = body(0, "BUY", assetId(jdbc, "000660"), 10, "FORWARD_BREAKOUT");
        Map<String, Object> before = state(jdbc, season);

        JsonNode q = quoted(season, buy);

        assertThat(q.get("fillPrice").asString()).isEqualTo("150000");
        assertThat(q.get("notional").asString()).isEqualTo("1500000");
        assertThat(q.get("fee").asString()).isEqualTo("225");
        assertThat(q.get("fxCost").asString()).as("국내 종목은 환전 비용이 없다").isEqualTo("0");
        assertThat(q.get("cashAfter").asString()).isEqualTo("28499775");
        // 199 × 150,000 = 29,850,000 + fee round(4,477.5) = 4,478 fits; 200 shares cost 30,004,500
        assertThat(q.get("maxBuyQuantity").asInt()).isEqualTo(199);
        for (String money : List.of("fillPrice", "notional", "fee", "fxCost", "cashAfter")) {
            assertThat(q.get(money).isString()).as(money).isTrue();
        }
        assertThat(state(jdbc, season)).as("견적은 원장·주문·보유·번호표를 건드리지 않는다").isEqualTo(before);
    }

    @Test
    void 미국_매수_견적의_수수료는_환전_비용을_포함하고_환전_몫을_따로_보인다() throws Exception {
        long season = startSeason(mvc);
        JsonNode q = quoted(season, body(0, "BUY", assetId(jdbc, "SPY"), 2, "MIDFIELDER"));

        assertThat(q.get("notional").asString()).isEqualTo("1296000");
        // round(1,296,000 × 0.0065) = 8,424, of which round(1,296,000 × 0.004) = 5,184 is the FX cost
        assertThat(q.get("fee").asString()).isEqualTo("8424");
        assertThat(q.get("fxCost").asString()).isEqualTo("5184");
        assertThat(q.get("cashAfter").asString()).isEqualTo("28695576");
        // 45 × 648,000 = 29,160,000 + 189,540 fits; 46 shares need 30,001,752
        assertThat(q.get("maxBuyQuantity").asInt()).isEqualTo(45);
    }

    @Test
    void 견적은_같은_본문의_다음_체결과_같다_매수와_매도() throws Exception {
        long season = startSeason(mvc);
        long kr = assetId(jdbc, "000660");
        long us = assetId(jdbc, "SPY");
        for (String buy : List.of(body(0, "BUY", kr, 7, "FORWARD_BREAKOUT"), body(0, "BUY", us, 3, "MIDFIELDER"),
                body(0, "BUY", kr, 4, null))) {
            JsonNode q = quoted(season, buy);
            MockHttpServletResponse fill = place(mvc, season, buy);
            assertThat(fill.getStatus()).isEqualTo(201);
            sameAsFill(q, json(fill));
        }

        fixTurn(jdbc, season, 1, Map.of("000660", "163457", "SPY", "701233"));
        for (String sell : List.of(body(1, "SELL", kr, 5, null), body(1, "SELL", us, 3, null))) {
            JsonNode q = quoted(season, sell);
            assertThat(q.get("maxBuyQuantity").isNull()).as("매도에는 최대 매수 수량이 없다").isTrue();
            MockHttpServletResponse fill = place(mvc, season, sell);
            assertThat(fill.getStatus()).isEqualTo(201);
            sameAsFill(q, json(fill));
        }
    }

    @Test
    void 최대_수량은_실제로_체결되고_하나_더는_현금_부족이다() throws Exception {
        long season = startSeason(mvc);
        long us = assetId(jdbc, "SPY");
        place(mvc, season, body(0, "BUY", assetId(jdbc, "000660"), 13, "FORWARD_BREAKOUT"));
        int max = quoted(season, body(0, "BUY", us, 1, "MIDFIELDER")).get("maxBuyQuantity").asInt();
        assertThat(max).isPositive();

        MockHttpServletResponse over = quote(season, body(0, "BUY", us, max + 1, "MIDFIELDER"));
        assertThat(over.getStatus()).isEqualTo(422);
        assertThat(code(over)).isEqualTo("INSUFFICIENT_CASH");
        assertThat(place(mvc, season, body(0, "BUY", us, max + 1, "MIDFIELDER")).getStatus()).isEqualTo(422);

        JsonNode q = quoted(season, body(0, "BUY", us, max, "MIDFIELDER"));
        assertThat(q.get("maxBuyQuantity").asInt()).isEqualTo(max);
        MockHttpServletResponse fill = place(mvc, season, body(0, "BUY", us, max, "MIDFIELDER"));
        assertThat(fill.getStatus()).isEqualTo(201);
        sameAsFill(q, json(fill));
        MockHttpServletResponse none = quote(season, body(0, "BUY", us, 1, null));
        assertThat(none.getStatus()).as("현금이 한 주에 모자라면 견적 자체가 422 다").isEqualTo(422);
        assertThat(code(none)).isEqualTo("INSUFFICIENT_CASH");
    }

    @Test
    void 주문이_거절할_요청은_같은_상태와_코드로_거절하고_아무것도_쓰지_않는다() throws Exception {
        long season = startSeason(mvc);
        long kr = assetId(jdbc, "000660");
        Map<String, Object> before = state(jdbc, season);
        Object[][] cases = {
                {body(0, "BUY", kr, Integer.MAX_VALUE, "FORWARD_BREAKOUT"), 422, "INSUFFICIENT_CASH"},
                {body(0, "BUY", kr, 1, null), 422, "POSITION_REQUIRED"},
                {body(0, "BUY", 999_999_999L, 1, "GOALKEEPER"), 422, "ASSET_NOT_IN_POOL"},
                {body(0, "SELL", kr, 1, null), 422, "INSUFFICIENT_HOLDING"},
                {body(0, "SELL", kr, 1, "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT"},
                {body(0, "BUY", kr, 0, "FORWARD_BREAKOUT"), 400, "INVALID_ARGUMENT"},
                {body(1, "BUY", kr, 1, "FORWARD_BREAKOUT"), 409, "TURN_MISMATCH"},
        };
        for (Object[] c : cases) {
            String request = (String) c[0];
            MockHttpServletResponse q = quote(season, request);
            MockHttpServletResponse o = place(mvc, season, request);
            assertThat(q.getStatus()).as(request).isEqualTo(c[1]).isEqualTo(o.getStatus());
            assertThat(code(q)).as(request).isEqualTo(c[2]).isEqualTo(code(o));
        }
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void 지나간_턴과_다음_턴의_견적은_409이다() throws Exception {
        long season = startSeason(mvc);
        long kr = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", kr, 3, "FORWARD_BREAKOUT"));
        fixTurn(jdbc, season, 1, Map.of());
        for (int turn : new int[] {0, 2}) {
            MockHttpServletResponse r = quote(season, body(turn, "BUY", kr, 1, null));
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("TURN_MISMATCH");
        }
        assertThat(quoted(season, body(1, "BUY", kr, 1, null)).get("fillPrice").asString()).isEqualTo("150000");
    }

    @Test
    void 남의_시즌과_없는_시즌은_똑같이_404다() throws Exception {
        long season = startSeason(mvc);
        String request = body(0, "BUY", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT");
        long other = jdbc.queryForObject(
                "INSERT INTO member (email, name, password) VALUES ('other@example.invalid', '다른 회원', '!') RETURNING id",
                Long.class);
        long owner = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        try {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", other, season);
            MockHttpServletResponse theirs = quote(season, request);
            MockHttpServletResponse missing = quote(999_999_999L, request);
            assertThat(theirs.getStatus()).isEqualTo(404);
            assertThat(missing.getStatus()).isEqualTo(404);
            assertThat(json(theirs).get("detail")).isEqualTo(json(missing).get("detail"));
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", owner, season);
            jdbc.update("DELETE FROM member WHERE id = ?", other);
        }
    }

    @Test
    void 응답_키는_record_구성_요소와_같고_날짜나_턴_정보가_없다() throws Exception {
        long season = startSeason(mvc);
        JsonNode q = quoted(season, body(0, "BUY", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT"));
        List<String> expected = new ArrayList<>();
        Arrays.stream(OrderQuoteDto.class.getRecordComponents()).forEach(c -> expected.add(c.getName()));
        List<String> actual = new ArrayList<>();
        q.propertyNames().forEach(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).doesNotContainAnyElementsOf(BuyOrderExposureTest.FORBIDDEN_KEYS)
                .doesNotContain("turnNo", "orderId", "yearMonth", "date");
    }
}
