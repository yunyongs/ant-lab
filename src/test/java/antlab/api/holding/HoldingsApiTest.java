package antlab.api.holding;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.ratio;
import static antlab.api.turn.TurnViewSupport.won;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * 현재 팀 조회(GET holdings): 합성 시즌. 매매가 없는 턴이면 총액·보유·포지션 네 값이 그 턴 {@code turn} 행과 같고,
 * 같은 턴에 매수하면 현금·보유가 바로 바뀌며 이번 턴에 산 종목이 선언 포지션에 들어간다. 수량 0 행은 빠지고 종목 번호 순이다.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class HoldingsApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private void assertPositionRatios(JsonNode o, int[] formation) {
        BigDecimal total = new BigDecimal(o.get("totalValue").asString());
        String[] positions = {"goalkeeper", "defender", "midfielder", "forward"};
        BigDecimal numerator = BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            BigDecimal v = new BigDecimal(o.get(positions[i] + "Value").asString());
            assertThat(o.get(positions[i] + "Weight").asString()).isEqualTo(ratio(v, total).toPlainString());
            BigDecimal diff = v.multiply(BigDecimal.valueOf(100)).subtract(BigDecimal.valueOf(formation[i]).multiply(total));
            assertThat(o.get(positions[i] + "Gap").asString())
                    .isEqualTo(ratio(diff, total.multiply(BigDecimal.valueOf(100))).toPlainString());
            numerator = numerator.add(diff.abs());
            assertThat(o.get("currentFormation").get(positions[i]).asInt()).isEqualTo(formation[i]);
        }
        assertThat(o.get("formationDeviation").asString())
                .isEqualTo(ratio(numerator, total.multiply(BigDecimal.valueOf(100))).toPlainString());
    }

    @Test
    void 매매_없는_턴이면_총액_보유_포지션_네_값이_그_턴_행과_같고_목록은_종목_번호_순이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        MockHttpServletResponse r = getHoldings(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode o = json(r);
        Map<String, Object> turn = jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = 1", season);
        assertThat(o.get("seasonId").asLong()).isEqualTo(season);
        assertThat(o.get("turnNo").asInt()).isEqualTo(1);
        assertThat(o.get("cashBalance").asString()).isEqualTo(won(turn.get("cash_balance")).toPlainString());
        assertThat(o.get("holdingsValue").asString()).isEqualTo(won(turn.get("holdings_value")).toPlainString());
        assertThat(o.get("totalValue").asString()).isEqualTo(won(turn.get("total_value")).toPlainString());
        assertThat(o.get("goalkeeperValue").asString()).isEqualTo(won(turn.get("goalkeeper_value")).toPlainString());
        assertThat(o.get("defenderValue").asString()).isEqualTo(won(turn.get("defender_value")).toPlainString());
        assertThat(o.get("midfielderValue").asString()).isEqualTo(won(turn.get("midfielder_value")).toPlainString());
        assertThat(o.get("forwardValue").asString()).isEqualTo(won(turn.get("forward_value")).toPlainString());
        assertPositionRatios(o, new int[] {10, 30, 30, 30});

        // 목록: 수량 > 0, 종목 번호 오름차순, 가격 = 그 턴 turn_price, 평균단가 = holding(소수 4자리 문자열)
        JsonNode list = o.get("holdings");
        assertThat(list).hasSize(4);
        List<Long> ids = new ArrayList<>();
        list.forEach(h -> ids.add(h.get("assetId").asLong()));
        assertThat(ids).isSorted();
        BigDecimal total = new BigDecimal(o.get("totalValue").asString());
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode h : list) {
            long asset = h.get("assetId").asLong();
            Map<String, Object> holding = jdbc.queryForMap("SELECT quantity, avg_price FROM holding WHERE season_id = ?"
                    + " AND asset_id = ?", season, asset);
            BigDecimal price = won(jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND turn_no = 1"
                    + " AND asset_id = ?", BigDecimal.class, season, asset));
            int quantity = (Integer) holding.get("quantity");
            assertThat(h.get("quantity").asInt()).isEqualTo(quantity);
            assertThat(h.get("price").asString()).isEqualTo(price.toPlainString());
            assertThat(h.get("avgPrice").asString()).matches("\\d+\\.\\d{4}")
                    .isEqualTo(((BigDecimal) holding.get("avg_price")).toPlainString());
            BigDecimal value = price.multiply(BigDecimal.valueOf(quantity));
            assertThat(h.get("value").asString()).isEqualTo(value.toPlainString());
            assertThat(h.get("weight").asString()).isEqualTo(ratio(value, total).toPlainString());
            assertThat(h.get("declaredPosition").asString()).isEqualTo(jdbc.queryForObject(
                    "SELECT declared_position FROM lineup WHERE season_id = ? AND asset_id = ? AND end_turn IS NULL",
                    String.class, season, asset));
            sum = sum.add(value);
        }
        assertThat(o.get("holdingsValue").asString()).isEqualTo(sum.toPlainString());
    }

    @Test
    void 같은_턴에_매수하면_현금과_보유가_바로_바뀌고_이번_턴에_산_종목이_선언_포지션에_들어간다() throws Exception {
        long season = startSeason(mvc);
        buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        buy(mvc, jdbc, season, 0, "069500", 100, "MIDFIELDER");
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        JsonNode before = json(getHoldings(mvc, season));
        assertThat(before.get("holdings")).hasSize(2);
        assertThat(before.get("defenderValue").asString()).isEqualTo("0");

        buy(mvc, jdbc, season, 1, "SPY", 5, "DEFENDER");
        JsonNode after = json(getHoldings(mvc, season));
        long spy = assetId(jdbc, "SPY");
        assertThat(after.get("holdings")).hasSize(3);
        JsonNode spyRow = null;
        for (JsonNode h : after.get("holdings")) {
            if (h.get("assetId").asLong() == spy) {
                spyRow = h;
            }
        }
        assertThat(spyRow).as("이번 턴에 산 종목").isNotNull();
        assertThat(spyRow.get("declaredPosition").asString()).isEqualTo("DEFENDER");
        assertThat(new BigDecimal(after.get("cashBalance").asString()))
                .isLessThan(new BigDecimal(before.get("cashBalance").asString()));
        assertThat(after.get("defenderValue").asString()).isEqualTo(spyRow.get("value").asString());
        BigDecimal total = new BigDecimal(after.get("cashBalance").asString())
                .add(new BigDecimal(after.get("holdingsValue").asString()));
        assertThat(after.get("totalValue").asString()).isEqualTo(total.toPlainString());
        assertPositionRatios(after, new int[] {10, 30, 30, 30});
        // 턴 1 스냅샷은 이 매수를 모른다 — 조회만 실시간이다.
        assertThat(jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 1", BigDecimal.class,
                season).setScale(0)).isEqualTo(new BigDecimal(before.get("totalValue").asString()));
    }

    @Test
    void 수량_0인_행은_목록에서_빠진다() throws Exception {
        long season = startSeason(mvc);
        buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        jdbc.update("INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (?, ?, 0, 0)", season,
                assetId(jdbc, "SPY"));
        JsonNode o = json(getHoldings(mvc, season));
        assertThat(o.get("holdings")).hasSize(1);
        assertThat(o.get("holdings").get(0).get("assetId").asLong()).isEqualTo(assetId(jdbc, "000660"));
    }

    @Test
    void 보유가_없으면_빈_목록이고_현금이_전부_골키퍼다() throws Exception {
        long season = startSeason(mvc);
        JsonNode o = json(getHoldings(mvc, season));
        assertThat(o.get("holdings")).isEmpty();
        assertThat(o.get("holdingsValue").asString()).isEqualTo("0");
        assertThat(o.get("goalkeeperValue").asString()).isEqualTo(o.get("totalValue").asString());
        assertThat(o.get("goalkeeperWeight").asString()).isEqualTo("1.00000000");
        assertThat(o.get("defenderWeight").asString()).isEqualTo("0.00000000");
    }
}
