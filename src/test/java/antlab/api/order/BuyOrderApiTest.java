package antlab.api.order;

import static antlab.api.order.OrderApiSupport.addAsset;
import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.money.MoneyMath;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
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
 * POST /api/v1/seasons/{id}/orders — 매수 정상 경로. 합성 고정본(실제 표본 아님)을 커밋하고 시험 뒤 지운다
 * (서비스 트랜잭션이 실제로 커밋되는 경로를 보려고 시험 트랜잭션을 쓰지 않는다).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BuyOrderApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 국내_신규_매수는_201이고_여섯_표가_함께_바뀐다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");

        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT"));

        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(r.getHeader("Location")).as("주문 단건 조회 경로가 없다").isNull();
        JsonNode o = json(r);
        // 금액은 원 단위 정수 문자열, 평균단가만 소수 4자리
        for (String money : List.of("fillPrice", "notional", "fee", "cashBalance")) {
            assertThat(o.get(money).isString()).isTrue();
            assertThat(o.get(money).asString()).matches("^\\d+$");
        }
        assertThat(o.get("avgPrice").asString()).isEqualTo("150000.0000");
        assertThat(o.get("fillPrice").asString()).isEqualTo("150000");
        assertThat(o.get("notional").asString()).isEqualTo("1500000");
        assertThat(o.get("fee").asString()).isEqualTo("225");
        assertThat(o.get("cashBalance").asString()).isEqualTo("28499775");
        assertThat(o.get("quantity").asInt()).isEqualTo(10);
        assertThat(o.get("holdingQuantity").asInt()).isEqualTo(10);
        assertThat(o.get("turnNo").asInt()).isZero();
        assertThat(o.get("side").asString()).isEqualTo("BUY");
        assertThat(o.get("assetId").asLong()).isEqualTo(asset);
        assertThat(o.get("declaredPosition").asString()).isEqualTo("FORWARD_BREAKOUT");
        assertThat(o.get("formationChangeId").isNull()).as("변경이 없으면 null").isTrue();
        assertThat(o.get("orderId").asLong()).isPositive();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE season_id = ?", season);
        assertThat(order).containsEntry("side", "BUY").containsEntry("type", "MARKET")
                .containsEntry("status", "FILLED").containsEntry("source", "DIRECT")
                .containsEntry("quantity", 10).containsEntry("turn_no", 0);
        assertThat(order.get("formation_change_id")).isNull();
        assertThat(order.get("turn_feed_id")).isNull();
        assertThat(order.get("limit_price")).isNull();
        Map<String, Object> exec = jdbc.queryForMap("SELECT * FROM execution WHERE order_id = ?", order.get("id"));
        assertThat((BigDecimal) exec.get("fill_price")).isEqualByComparingTo("150000");
        assertThat((BigDecimal) exec.get("fee")).isEqualByComparingTo("225");
        assertThat((BigDecimal) exec.get("tax")).isEqualByComparingTo("0");
        assertThat(exec.get("realized_pnl")).isNull();
        Map<String, Object> holding = jdbc.queryForMap("SELECT * FROM holding WHERE season_id = ?", season);
        assertThat(holding.get("quantity")).isEqualTo(10);
        assertThat((BigDecimal) holding.get("avg_price")).isEqualByComparingTo("150000");
        // 원장: DEPOSIT → BUY → FEE (번호 순), 모두 이 주문을 가리킨다
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT type, amount, balance_after, ref_order_id FROM cash_ledger WHERE season_id = ? ORDER BY id", season);
        assertThat(ledger).extracting(m -> m.get("type")).containsExactly("DEPOSIT", "BUY", "FEE");
        assertThat((BigDecimal) ledger.get(1).get("amount")).isEqualByComparingTo("-1500000");
        assertThat((BigDecimal) ledger.get(1).get("balance_after")).isEqualByComparingTo("28500000");
        assertThat((BigDecimal) ledger.get(2).get("amount")).isEqualByComparingTo("-225");
        assertThat((BigDecimal) ledger.get(2).get("balance_after")).isEqualByComparingTo("28499775");
        assertThat(ledger.get(1).get("ref_order_id")).isEqualTo(order.get("id"));
        assertThat(ledger.get(2).get("ref_order_id")).isEqualTo(order.get("id"));
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, season))
                .isEqualByComparingTo("28499775");
        Map<String, Object> lineup = jdbc.queryForMap("SELECT * FROM lineup WHERE season_id = ?", season);
        assertThat(lineup).containsEntry("declared_position", "FORWARD_BREAKOUT").containsEntry("start_turn", 0);
        assertThat(lineup.get("end_turn")).isNull();

        // 같은 DTO 로 읽는 시즌 한 개의 잔액이 응답과 같다
        JsonNode season2 = json(mvc.get().uri("/api/v1/seasons/" + season).exchange().getResponse());
        assertThat(season2.get("cashBalance").asString()).isEqualTo(o.get("cashBalance").asString());
    }

    @Test
    void 미국_신규_매수는_환전_요율을_더해_수수료를_낸다() throws Exception {
        long season = startSeason(mvc);
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", assetId(jdbc, "SPY"), 2, "DEFENDER"));
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(o.get("fillPrice").asString()).isEqualTo("648000");
        assertThat(o.get("notional").asString()).isEqualTo("1296000");
        // 1,296,000 × (0.0025 + 0.004) = 8,424
        assertThat(o.get("fee").asString()).isEqualTo("8424");
        assertThat(o.get("cashBalance").asString()).isEqualTo("28695576");
        assertThat(o.get("declaredPosition").asString()).isEqualTo("DEFENDER");
    }

    @Test
    void 같은_종목_추가_매수는_수량을_더하고_이동평균을_쓰며_구간을_새로_열지_않는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "069500");
        place(mvc, season, body(0, "BUY", asset, 100, "MIDFIELDER"));
        // 두 번째: 선언 생략 → 열린 구간의 값을 쓴다. 같은 가격이라 평균단가는 그대로
        JsonNode second = json(place(mvc, season, body(0, "BUY", asset, 50, null)));
        assertThat(second.get("holdingQuantity").asInt()).isEqualTo(150);
        assertThat(second.get("declaredPosition").asString()).isEqualTo("MIDFIELDER");
        assertThat(new BigDecimal(second.get("avgPrice").asString()))
                .isEqualByComparingTo(MoneyMath.avgPrice(100, new BigDecimal("35000"), 50, new BigDecimal("35000")));
        // 세 번째: 같은 선언을 다시 줘도 통과
        MockHttpServletResponse third = place(mvc, season, body(0, "BUY", asset, 1, "MIDFIELDER"));
        assertThat(third.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lineup WHERE season_id = ?", Long.class, season)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holding WHERE season_id = ?", Long.class, season)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE season_id = ?", Integer.class, season))
                .isEqualTo(151);
    }

    @Test
    void 수수료가_반올림_경계_점오인_종목은_올림되고_원장_수수료와_체결_수수료가_같다() throws Exception {
        long season = startSeason(mvc);
        // 국내: 10,000원 × 1주 × 0.00015 = 1.5 → 2. 미국: 1,000원 × 1주 × 0.0065 = 6.5 → 7
        long kr = addAsset(jdbc, season, "KR", "888801", "10000");
        long us = addAsset(jdbc, season, "US", "BOUND5", "1000");
        JsonNode a = json(place(mvc, season, body(0, "BUY", kr, 1, "GOALKEEPER")));
        JsonNode b = json(place(mvc, season, body(0, "BUY", us, 1, "GOALKEEPER")));
        assertThat(a.get("fee").asString()).isEqualTo("2");
        assertThat(b.get("fee").asString()).isEqualTo("7");
        List<BigDecimal> fees = jdbc.queryForList("SELECT amount FROM cash_ledger WHERE season_id = ? AND type = 'FEE' ORDER BY id",
                BigDecimal.class, season);
        assertThat(fees).hasSize(2);
        assertThat(fees.get(0)).isEqualByComparingTo("-2");
        assertThat(fees.get(1)).isEqualByComparingTo("-7");
        assertThat(jdbc.queryForList("SELECT fee FROM execution ORDER BY id", BigDecimal.class)).hasSize(2)
                .satisfies(l -> {
                    assertThat(l.get(0)).isEqualByComparingTo("2");
                    assertThat(l.get(1)).isEqualByComparingTo("7");
                });
    }

    @Test
    void 미국_수수료는_합산_요율로_한_번만_반올림한다() throws Exception {
        long season = startSeason(mvc);
        // 합성 가격 100원 × 1주: 거래 요율 0.0025 → 0.25(내림), 환전 요율 0.004 → 0.4(내림), 합산 요율 0.0065 → 0.65(올림).
        // 항목별로 반올림해 더하면 0 + 0 = 0 이고, 합산 요율로 한 번 반올림하면 1 이다. 기대값은 손으로 계산한 "1".
        long us = addAsset(jdbc, season, "US", "BOUND6", "100");
        JsonNode o = json(place(mvc, season, body(0, "BUY", us, 1, "GOALKEEPER")));
        assertThat(o.get("fee").asString()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT fee FROM execution", BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForList("SELECT amount FROM cash_ledger WHERE season_id = ? AND type = 'FEE'",
                BigDecimal.class, season)).hasSize(1).first().satisfies(a -> assertThat(a).isEqualByComparingTo("-1"));
    }

    @Test
    void 수수료가_0원이면_수수료_원장_행을_만들지_않는다() throws Exception {
        long season = startSeason(mvc);
        // 국내 1원 × 1주 × 0.00015 = 0.00015 → 0
        long cheap = addAsset(jdbc, season, "KR", "888802", "1");
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", cheap, 1, "GOALKEEPER"));
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(json(r).get("fee").asString()).isEqualTo("0");
        assertThat(json(r).get("cashBalance").asString()).isEqualTo("29999999");
        assertThat(jdbc.queryForList("SELECT type FROM cash_ledger WHERE season_id = ? ORDER BY id", String.class, season))
                .containsExactly("DEPOSIT", "BUY");
        assertThat(jdbc.queryForObject("SELECT fee FROM execution", BigDecimal.class)).isEqualByComparingTo("0");
    }
}
