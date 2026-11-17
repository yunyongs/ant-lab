package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The analysis of a finished 12-turn season of the synthetic fixture. The fixture pool has the market pair but none of the
 * other representative assets, so only the market baseline can be built here (the other three are reported as unavailable);
 * the four-baseline path runs in the end-to-end test with a table of fake tickers. The discipline numbers are checked by
 * hand on prices and fills written straight into the tables.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAnalysisApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode analysis(long season) throws Exception {
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentType()).startsWith("application/json");
        return json(r);
    }

    private JsonNode report(long season) throws Exception {
        return json(ReportApiSupport.getReport(mvc, season));
    }

    @Test
    void 끝난_시즌은_200이고_시장_기준선만_만들어진다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        JsonNode a = analysis(season);
        AnalysisAssertions.assertConsistent(a, report(season), 1);
        assertThat(a.get("decomposition").isNull()).isTrue();
        JsonNode market = a.get("baselines").get(0);
        assertThat(market.get("legs").get(0).get("asset").asString()).isEqualTo("KR:069500");
        assertThat(market.get("legs").get(0).get("slot").asString()).isEqualTo("MARKET");
        assertThat(market.get("legs").get(0).get("sharePercent").asInt()).isEqualTo(50);
        assertThat(market.get("legs").get(1).get("asset").asString()).isEqualTo("US:SPY");
        // turn 0 is after the opening purchase: value + fee = initial cash, give or take what the lots could not buy
        assertThat(AnalysisAssertions.won(market.get("turnValues").get(0)).longValue()).isBetween(29_000_000L, 30_000_000L);
        for (int i = 1; i < 4; i++) {
            assertThat(a.get("baselines").get(i).get("available").asBoolean()).isFalse();
            assertThat(a.get("baselines").get(i).get("bondLegSubstituted").asBoolean()).isFalse();
        }
        // only turn 0 was traded: nothing is judged and no share can be computed
        JsonNode d = a.get("discipline");
        assertThat(d.get("sellNotional").asString()).isEqualTo("0");
        assertThat(d.get("buyNotional").asString()).isEqualTo("0");
        assertThat(d.get("panicSellShare").isNull()).isTrue();
        assertThat(d.get("chaseBuyShare").isNull()).isTrue();
        assertThat(d.get("undisciplinedShare").isNull()).isTrue();
        assertThat(d.get("formationChanges").asInt()).isZero();
    }

    /**
     * Prices of BRK.B are set by hand: 1,000,000 at turn 3, then 900,000 (−10 %), 900,000, 990,000 (+10 % exactly) and 990,000.
     * Fills are at 1,000 won a share, so every notional is the quantity times 1,000. Team value falls 10 % at turn 4, where
     * a formation change lowers the forward target; the turn-5 sell and the turn-7 buy meet a flat price.
     */
    @Test
    void 패닉_매도와_추격_매수의_손_계산_금액이_나온다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long brk = assetId(jdbc, "BRK.B");
        long[] prices = {1_000_000, 900_000, 900_000, 990_000, 990_000};
        for (int i = 0; i < prices.length; i++) {
            assertThat(jdbc.update("UPDATE turn_price SET price = ? WHERE season_id = ? AND asset_id = ? AND turn_no = ?",
                    prices[i], season, brk, 3 + i)).isEqualTo(1);
        }
        ReportApiSupport.fill(jdbc, season, brk, 4, "SELL", 2);
        ReportApiSupport.fill(jdbc, season, brk, 5, "SELL", 1);
        ReportApiSupport.fill(jdbc, season, brk, 6, "BUY", 3);
        ReportApiSupport.fill(jdbc, season, brk, 7, "BUY", 1);
        long total3 = jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 3", Long.class, season);
        long fallen = total3 * 90 / 100;
        jdbc.update("UPDATE turn SET cash_balance = ?, holdings_value = 0, total_value = ?, goalkeeper_value = ?, defender_value = 0, "
                + "midfielder_value = 0, forward_value = 0 WHERE season_id = ? AND turn_no = 4", fallen, fallen, fallen, season);
        jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, "
                + "before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward) "
                + "VALUES (?, 4, 10, 30, 30, 30, 10, 50, 30, 10)", season);
        JsonNode d = analysis(season).get("discipline");
        // sells: 2 x 1,000 (the asset fell 10 %) and 1 x 1,000 (flat); only the first is panic
        assertThat(d.get("sellNotional").asString()).isEqualTo("3000");
        assertThat(d.get("panicSellNotional").asString()).isEqualTo("2000");
        assertThat(d.get("panicSellShare").asString()).isEqualTo("0.66666667");
        // buys: 3 x 1,000 (rose exactly 10 %, boundary class) and 1 x 1,000 (flat)
        assertThat(d.get("buyNotional").asString()).isEqualTo("4000");
        assertThat(d.get("chaseBuyNotional").asString()).isEqualTo("3000");
        assertThat(d.get("chaseBuyShare").asString()).isEqualTo("0.75000000");
        assertThat(d.get("tradeNotional").asString()).isEqualTo("7000");
        assertThat(d.get("undisciplinedShare").asString()).isEqualTo("0.71428571");
        assertThat(d.get("formationChanges").asInt()).isEqualTo(1);
        assertThat(d.get("panicFormationChanges").asInt()).as("team fell 10 % and the forward target 30 → 10").isEqualTo(1);
    }

    @Test
    void 한_턴에_변경이_두_행이면_패닉_변경도_행_기준으로_센다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long total5 = jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 5", Long.class, season);
        long fallen = total5 * 90 / 100;
        jdbc.update("UPDATE turn SET cash_balance = ?, holdings_value = 0, total_value = ?, goalkeeper_value = ?, defender_value = 0, "
                + "midfielder_value = 0, forward_value = 0 WHERE season_id = ? AND turn_no = 6", fallen, fallen, fallen, season);
        String sql = "INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, "
                + "before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward) VALUES (?, 6, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql, season, 10, 30, 30, 30, 10, 40, 30, 20);
        jdbc.update(sql, season, 10, 40, 30, 20, 10, 50, 30, 10);
        JsonNode d = analysis(season).get("discipline");
        assertThat(d.get("formationChanges").asInt()).isEqualTo(2);
        assertThat(d.get("panicFormationChanges").asInt()).as("both rows of the panic turn count").isEqualTo(2);
    }
}
