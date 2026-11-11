package antlab.api.order;

import static antlab.api.order.OrderApiSupport.addAsset;
import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.fixTurn;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnViewSupport.changeFormation;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
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
 * POST /api/v1/seasons/{id}/orders — 매도 정상 경로. 합성 고정본(실제 시세 아님). 기대값은 손으로 정한 가격에서
 * 시험이 따로 계산한다(운영 코드의 {@code MoneyMath} 를 쓰지 않는다). 초기 자금 30,000,000원, 국내 요율 0.00015,
 * 미국 요율 0.0025 + 0.004. 가격을 손으로 바꾸는 시험은 {@code fixTurn} 으로 턴을 직접 넘기고(대사 불가),
 * 대사 17개를 보는 시험은 실제 턴 전환을 쓴다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SellOrderApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private void assertAdvance(long season, int turn) {
        assertThat(advance(mvc, season, turn).getStatus()).as("전환 " + turn).isEqualTo(201);
    }

    private long matchRows(long season, int turn) {
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ? AND status = 'MATCH'",
                Long.class, season, turn);
    }

    @Test
    void 일_턴_부분_매도는_201이고_실현_손익_원장_보유_잔액이_맞는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        JsonNode buy = json(place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT"))); // 150,000원 10주
        assertThat(buy.has("realizedPnl")).as("매수 응답에도 키가 있다").isTrue();
        assertThat(buy.get("realizedPnl").isNull()).as("매수의 실현 손익은 null").isTrue();
        fixTurn(jdbc, season, 1, Map.of("000660", "160000"));

        MockHttpServletResponse r = place(mvc, season, body(1, "SELL", asset, 4, null));

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        // 대금 160,000 × 4 = 640,000 / 수수료 640,000 × 0.00015 = 96 / 실현 손익 (160,000 − 150,000) × 4 = 40,000
        assertThat(o.get("side").asString()).isEqualTo("SELL");
        assertThat(o.get("turnNo").asInt()).isEqualTo(1);
        assertThat(o.get("fillPrice").asString()).isEqualTo("160000");
        assertThat(o.get("notional").asString()).isEqualTo("640000");
        assertThat(o.get("fee").asString()).isEqualTo("96");
        assertThat(o.get("realizedPnl").isString()).isTrue();
        assertThat(o.get("realizedPnl").asString()).isEqualTo("40000.0000");
        // 28,499,775 (매수 뒤) + 640,000 − 96
        assertThat(o.get("cashBalance").asString()).isEqualTo("29139679");
        assertThat(o.get("holdingQuantity").asInt()).isEqualTo(6);
        assertThat(o.get("avgPrice").asString()).as("매도는 평균단가를 바꾸지 않는다").isEqualTo("150000.0000");
        assertThat(o.get("declaredPosition").asString()).isEqualTo("FORWARD_BREAKOUT");
        assertThat(o.get("formationChangeId").isNull()).isTrue();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", o.get("orderId").asLong());
        assertThat(order).containsEntry("side", "SELL").containsEntry("type", "MARKET").containsEntry("status", "FILLED")
                .containsEntry("source", "DIRECT").containsEntry("quantity", 4).containsEntry("turn_no", 1);
        assertThat(order.get("limit_price")).isNull();
        assertThat(order.get("turn_feed_id")).isNull();
        Map<String, Object> exec = jdbc.queryForMap("SELECT * FROM execution WHERE order_id = ?", order.get("id"));
        assertThat((BigDecimal) exec.get("fill_price")).isEqualByComparingTo("160000");
        assertThat((BigDecimal) exec.get("fee")).isEqualByComparingTo("96");
        assertThat((BigDecimal) exec.get("tax")).isEqualByComparingTo("0");
        assertThat((BigDecimal) exec.get("realized_pnl")).isEqualByComparingTo("40000");
        // 원장: DEPOSIT → BUY → FEE → SELL(+대금) → FEE(−수수료), 매도 두 행은 이 주문을 가리킨다
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT type, amount, balance_after, ref_order_id FROM cash_ledger WHERE season_id = ? ORDER BY id", season);
        assertThat(ledger).extracting(m -> m.get("type")).containsExactly("DEPOSIT", "BUY", "FEE", "SELL", "FEE");
        assertThat((BigDecimal) ledger.get(3).get("amount")).isEqualByComparingTo("640000");
        assertThat((BigDecimal) ledger.get(3).get("balance_after")).isEqualByComparingTo("29139775");
        assertThat((BigDecimal) ledger.get(4).get("amount")).isEqualByComparingTo("-96");
        assertThat((BigDecimal) ledger.get(4).get("balance_after")).isEqualByComparingTo("29139679");
        assertThat(ledger.get(3).get("ref_order_id")).isEqualTo(order.get("id"));
        assertThat(ledger.get(4).get("ref_order_id")).isEqualTo(order.get("id"));
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, season))
                .isEqualByComparingTo("29139679");
        Map<String, Object> holding = jdbc.queryForMap("SELECT * FROM holding WHERE season_id = ?", season);
        assertThat(holding.get("quantity")).isEqualTo(6);
        assertThat((BigDecimal) holding.get("avg_price")).isEqualByComparingTo("150000");
        assertThat(jdbc.queryForObject("SELECT end_turn FROM lineup WHERE season_id = ?", Integer.class, season))
                .as("부분 매도는 구간을 닫지 않는다").isNull();
        // 응답의 잔액 = 바로 뒤 보유 조회의 현금, 보유 수량·평균단가도 같다
        JsonNode holdings = json(getHoldings(mvc, season));
        assertThat(holdings.get("cashBalance").asString()).isEqualTo(o.get("cashBalance").asString());
        assertThat(holdings.get("holdings").get(0).get("quantity").asInt()).isEqualTo(6);
        assertThat(holdings.get("holdings").get(0).get("avgPrice").asString()).isEqualTo("150000.0000");
    }

    @Test
    void 전량_매도는_보유_행을_0과_0으로_남기고_구간을_현재_턴에_닫는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT"));
        fixTurn(jdbc, season, 1, Map.of("000660", "160000"));

        JsonNode o = json(place(mvc, season, body(1, "SELL", asset, 10, null)));

        // 대금 1,600,000 / 수수료 240 / 실현 손익 100,000 / 잔액 28,499,775 + 1,600,000 − 240
        assertThat(o.get("notional").asString()).isEqualTo("1600000");
        assertThat(o.get("fee").asString()).isEqualTo("240");
        assertThat(o.get("realizedPnl").asString()).as("평균단가가 0 이 되기 전 값으로 계산").isEqualTo("100000.0000");
        assertThat(o.get("cashBalance").asString()).isEqualTo("30099535");
        assertThat(o.get("holdingQuantity").asInt()).isZero();
        assertThat(o.get("avgPrice").asString()).isEqualTo("0.0000");
        assertThat(o.get("declaredPosition").asString()).as("방금 닫은 구간의 선언").isEqualTo("FORWARD_BREAKOUT");
        Map<String, Object> holding = jdbc.queryForMap("SELECT * FROM holding WHERE season_id = ?", season);
        assertThat(holding.get("quantity")).isEqualTo(0);
        assertThat((BigDecimal) holding.get("avg_price")).isEqualByComparingTo("0");
        Map<String, Object> lineup = jdbc.queryForMap("SELECT * FROM lineup WHERE season_id = ?", season);
        assertThat(lineup.get("start_turn")).isEqualTo(0);
        assertThat(lineup.get("end_turn")).isEqualTo(1);
        // 보유 목록에서 빠지고 현금만 남는다
        JsonNode holdings = json(getHoldings(mvc, season));
        assertThat(holdings.get("holdings")).isEmpty();
        assertThat(holdings.get("cashBalance").asString()).isEqualTo("30099535");
        assertThat(holdings.get("holdingsValue").asString()).isEqualTo("0");
    }

    @Test
    void 추가_매수_뒤_매도의_실현_손익은_체결_직전_이동평균_단가로_계산한다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT")); // 150,000원 10주
        fixTurn(jdbc, season, 1, Map.of("000660", "180000"));
        place(mvc, season, body(1, "BUY", asset, 10, null)); // 180,000원 10주 → 평균 165,000
        fixTurn(jdbc, season, 2, Map.of("000660", "200000"));

        JsonNode o = json(place(mvc, season, body(2, "SELL", asset, 5, null)));

        // (200,000 − 165,000) × 5 = 175,000
        assertThat(o.get("realizedPnl").asString()).isEqualTo("175000.0000");
        assertThat(o.get("avgPrice").asString()).isEqualTo("165000.0000");
        assertThat(o.get("holdingQuantity").asInt()).isEqualTo(15);
    }

    @Test
    void 평균단가가_소수여도_실현_손익은_소수_넷째_자리에서_정확하다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT")); // 150,000원 1주
        fixTurn(jdbc, season, 1, Map.of("000660", "150001"));
        place(mvc, season, body(1, "BUY", asset, 2, null)); // 평균 (150,000 + 2 × 150,001) / 3 = 150000.6667
        fixTurn(jdbc, season, 2, Map.of("000660", "150001"));

        JsonNode o = json(place(mvc, season, body(2, "SELL", asset, 3, null)));

        // (150,001 − 150000.6667) × 3 = 0.9999
        assertThat(o.get("avgPrice").asString()).isEqualTo("0.0000");
        assertThat(o.get("realizedPnl").asString()).isEqualTo("0.9999");
    }

    @Test
    void 손실_매도는_음수_실현_손익이다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "069500");
        place(mvc, season, body(0, "BUY", asset, 100, "MIDFIELDER")); // 35,000원
        fixTurn(jdbc, season, 1, Map.of("069500", "30000"));

        JsonNode o = json(place(mvc, season, body(1, "SELL", asset, 40, null)));

        // (30,000 − 35,000) × 40 = −200,000
        assertThat(o.get("realizedPnl").asString()).isEqualTo("-200000.0000");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT realized_pnl FROM execution WHERE order_id = ?", BigDecimal.class,
                o.get("orderId").asLong())).isEqualByComparingTo("-200000");
    }

    @Test
    void 같은_턴_매수_전량_매도_재매수는_길이_없는_구간과_새_구간을_남기고_대사가_맞는다() throws Exception {
        long season = startSeason(mvc);
        // 3종목만 0턴에 사고 BRK.B 는 1턴에서 다룬다
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, season, 0, "069500", 100, "MIDFIELDER");
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, season, 0, "SPY", 5, "DEFENDER");
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, season, 0, "BRK.B", 5, "GOALKEEPER");
        assertAdvance(season, 0);
        long brk = assetId(jdbc, "BRK.B");
        assertThat(place(mvc, season, body(1, "SELL", brk, 5, null)).getStatus()).isEqualTo(201); // 0턴 구간 [0,1) 닫힘
        assertThat(place(mvc, season, body(1, "BUY", brk, 2, "GOALKEEPER")).getStatus()).isEqualTo(201); // [1,∞)
        assertThat(place(mvc, season, body(1, "SELL", brk, 2, null)).getStatus()).isEqualTo(201); // [1,1) 길이 없음
        assertThat(place(mvc, season, body(1, "BUY", brk, 3, "DEFENDER")).getStatus()).isEqualTo(201); // 새 [1,∞)

        List<Map<String, Object>> lineups = jdbc.queryForList(
                "SELECT declared_position, start_turn, end_turn FROM lineup WHERE season_id = ? AND asset_id = ? ORDER BY id",
                season, brk);
        assertThat(lineups).hasSize(3);
        assertThat(lineups.get(1)).containsEntry("start_turn", 1).containsEntry("end_turn", 1);
        assertThat(lineups.get(2)).containsEntry("declared_position", "DEFENDER").containsEntry("start_turn", 1);
        assertThat(lineups.get(2).get("end_turn")).isNull();

        assertAdvance(season, 1);
        assertThat(matchRows(season, 2)).as("규칙 17·18 을 포함한 17개").isEqualTo(17L);
    }

    @Test
    void 전량_매도_다음_턴_재매수는_선언이_필수이고_새_구간은_그_턴에_시작한다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertAdvance(season, 0);
        long asset = assetId(jdbc, "000660");
        assertThat(place(mvc, season, body(1, "SELL", asset, 10, null)).getStatus()).isEqualTo(201);
        assertAdvance(season, 1);

        MockHttpServletResponse missing = place(mvc, season, body(2, "BUY", asset, 2, null));
        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(OrderApiSupport.code(missing)).isEqualTo("POSITION_REQUIRED");
        assertThat(place(mvc, season, body(2, "BUY", asset, 2, "FORWARD_TARGET")).getStatus()).isEqualTo(201);

        assertThat(jdbc.queryForList("SELECT declared_position || ':' || start_turn || ':' || coalesce(end_turn::text, '-')"
                + " FROM lineup WHERE season_id = ? AND asset_id = ? ORDER BY id", String.class, season, asset))
                .containsExactly("FORWARD_BREAKOUT:0:1", "FORWARD_TARGET:2:-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holding WHERE season_id = ? AND asset_id = ?", Long.class, season, asset))
                .as("보유 행은 하나를 계속 쓴다").isEqualTo(1L);
        assertAdvance(season, 2);
        assertThat(matchRows(season, 3)).isEqualTo(17L);
    }

    @Test
    void 미국_종목_매도_수수료는_합산_요율에서_한_번_반올림한다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "SPY");
        place(mvc, season, body(0, "BUY", asset, 2, "DEFENDER")); // 648,000원
        fixTurn(jdbc, season, 1, Map.of("SPY", "700000"));

        JsonNode o = json(place(mvc, season, body(1, "SELL", asset, 1, null)));

        // 700,000 × (0.0025 + 0.004) = 4,550 / 실현 손익 (700,000 − 648,000) × 1
        assertThat(o.get("fee").asString()).isEqualTo("4550");
        assertThat(o.get("realizedPnl").asString()).isEqualTo("52000.0000");
    }

    @Test
    void 수수료가_정확히_반이면_올림한다() throws Exception {
        long season = startSeason(mvc);
        // 1,000원 × 0.0065 = 6.5 → 7
        long asset = addAsset(jdbc, season, "US", "TST.HALF", "1000");
        JsonNode buy = json(place(mvc, season, body(0, "BUY", asset, 1, "GOALKEEPER")));
        assertThat(buy.get("fee").asString()).isEqualTo("7");

        JsonNode sell = json(place(mvc, season, body(0, "SELL", asset, 1, null)));

        assertThat(sell.get("fee").asString()).isEqualTo("7");
        assertThat(sell.get("realizedPnl").asString()).isEqualTo("0.0000");
        assertThat(sell.get("cashBalance").asString()).as("30,000,000 − 7 − 7").isEqualTo("29999986");
    }

    @Test
    void 매도가_낀_턴의_전환_응답은_바로_뒤_턴_조회와_같고_닫힌_구간은_다음_스냅샷에서_빠진다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertAdvance(season, 0);
        assertThat(place(mvc, season, body(1, "SELL", assetId(jdbc, "069500"), 100, null)).getStatus()).isEqualTo(201); // 유일한 미드필더
        assertThat(place(mvc, season, body(1, "SELL", assetId(jdbc, "000660"), 3, null)).getStatus()).isEqualTo(201);

        MockHttpServletResponse advanced = advance(mvc, season, 1);

        assertThat(advanced.getStatus()).isEqualTo(201);
        assertThat(json(getTurn(mvc, season, 2))).isEqualTo(json(advanced));
        assertThat(json(advanced).get("midfielderValue").asString()).as("닫힌 구간은 스냅샷 포지션 값에서 빠진다").isEqualTo("0");
        assertThat(matchRows(season, 2)).isEqualTo(17L);
    }

    @Test
    void 전술_변경이_있는_턴의_매도는_그_변경에_묶인다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertAdvance(season, 0);
        changeFormation(jdbc, season, 1, 10, 30, 40, 20);
        long change = jdbc.queryForObject("SELECT max(id) FROM formation_change WHERE season_id = ?", Long.class, season);

        JsonNode o = json(place(mvc, season, body(1, "SELL", assetId(jdbc, "000660"), 2, null)));

        assertThat(o.get("formationChangeId").asLong()).isEqualTo(change);
        assertThat(jdbc.queryForObject("SELECT formation_change_id FROM orders WHERE id = ?", Long.class, o.get("orderId").asLong()))
                .isEqualTo(change);
    }

    @Test
    void 영_턴_매도도_받고_다음_전환의_대사_17개가_맞는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        MockHttpServletResponse r = place(mvc, season, body(0, "SELL", assetId(jdbc, "000660"), 4, null));

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(o.get("turnNo").asInt()).isZero();
        assertThat(o.get("realizedPnl").asString()).isEqualTo("0.0000");
        assertThat(o.get("holdingQuantity").asInt()).isEqualTo(6);
        assertAdvance(season, 0);
        assertThat(matchRows(season, 1)).isEqualTo(17L);
    }

    @Test
    void 매도의_첫_문장은_시즌_잠금이고_그다음_계좌_잠금이며_보유와_구간은_잠그지_않는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 10, "FORWARD_BREAKOUT"));

        SqlRecorder.start();
        MockHttpServletResponse r = place(mvc, season, body(0, "SELL", asset, 10, null));
        List<String> sql = SqlRecorder.stop().stream().map(q -> q.toLowerCase().replaceAll("\\s+", " ")).toList();
        sql.forEach(q -> System.out.println("[X2] " + q));

        assertThat(r.getStatus()).isEqualTo(201);
        int seasonLock = indexOf(sql, " from season ", "for no key update");
        int accountLock = indexOf(sql, " from cash_account ", "for no key update");
        assertThat(seasonLock).isGreaterThanOrEqualTo(0);
        assertThat(accountLock).isGreaterThan(seasonLock);
        assertThat(sql.subList(0, seasonLock)).allMatch(q -> q.contains(" from member ") && !q.contains("for "));
        assertThat(sql.stream().filter(q -> q.startsWith("select") && q.contains(" from season ")).toList()).hasSize(1);
        assertThat(sql).noneMatch(q -> (q.contains(" from holding ") || q.contains(" from lineup ")) && q.contains("for "));
        // 쓰기 순서: orders → execution → (holding·cash_ledger INSERT) → … → 커밋 때 UPDATE
        int order = indexOfStart(sql, "insert into orders");
        int execution = indexOfStart(sql, "insert into execution");
        int ledger = indexOfStart(sql, "insert into cash_ledger");
        assertThat(order).isGreaterThan(accountLock);
        assertThat(execution).isGreaterThan(order);
        assertThat(ledger).isGreaterThan(execution);
        assertThat(sql).anyMatch(q -> q.startsWith("update holding"));
        assertThat(sql).anyMatch(q -> q.startsWith("update lineup"));
        assertThat(sql).anyMatch(q -> q.startsWith("update cash_account"));
        assertThat(sql).noneMatch(q -> q.startsWith("insert into lineup"));
    }

    private static int indexOf(List<String> sql, String from, String lock) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains(from) && sql.get(i).contains(lock)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfStart(List<String> sql, String prefix) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
