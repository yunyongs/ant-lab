package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.turn.RankingSupport;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** Assets the player was pointed at in a turn, read the other way round: the ranking endpoint's lists plus the stored feed rows. */
    private Set<Long> pointed(long season, int turn, boolean up) throws Exception {
        JsonNode board = json(getRanking(mvc, season, turn));
        Set<Long> ids = new HashSet<>();
        for (String list : up ? List.of("gainers", "attention") : List.of("losers")) {
            board.get(list).forEach(row -> ids.add(row.get("assetId").asLong()));
        }
        ids.addAll(jdbc.queryForList("SELECT asset_id FROM turn_feed WHERE season_id = ? AND turn_no = ? AND asset_id IS NOT NULL "
                + "AND direction = ? AND kind IN (" + (up ? "'RISE', 'ECHO'" : "'FALL', 'ECHO'") + ")", Long.class, season, turn,
                up ? "UP" : "DOWN"));
        return ids;
    }

    private static boolean followed(String side, long asset, Set<Long> up, Set<Long> down) {
        return side.equals("BUY") ? up.contains(asset) : down.contains(asset);
    }

    @Test
    void 소음_반응은_순위판과_피드로_손_계산한_값과_같다() throws Exception {
        long season = RankingSupport.playedSeason(mvc, jdbc, 12);
        Set<Long> up3 = pointed(season, 3, true);
        Set<Long> down3 = pointed(season, 3, false);
        Set<Long> up6 = pointed(season, 6, true);
        Set<Long> down6 = pointed(season, 6, false);
        assertThat(up3).as("the seeded attention list always points at something").isNotEmpty();
        long pointedAsset = up3.iterator().next();
        long other = jdbc.queryForObject("SELECT id FROM asset WHERE name LIKE '가짜%' AND id <> ? ORDER BY id LIMIT 1", Long.class,
                pointedAsset);
        long spy = assetId(jdbc, "SPY");
        long brk = assetId(jdbc, "BRK.B");
        // fills are at 1,000 won a share
        ReportApiSupport.fill(jdbc, season, pointedAsset, 3, "BUY", 3);
        ReportApiSupport.fill(jdbc, season, other, 3, "BUY", 1);
        ReportApiSupport.fill(jdbc, season, spy, 6, "SELL", 2);
        ReportApiSupport.fill(jdbc, season, brk, 6, "SELL", 1);

        long followed3 = 3000 + (followed("BUY", other, up3, down3) ? 1000 : 0);
        long followed6 = (followed("SELL", spy, up6, down6) ? 2000 : 0) + (followed("SELL", brk, up6, down6) ? 1000 : 0);
        // mean of 3 turns' shares over two trading turns, as one division: (f3 / 4000 + f6 / 3000) / 2
        BigDecimal expected = BigDecimal.valueOf(followed3 * 3000 + followed6 * 4000)
                .divide(BigDecimal.valueOf(2L * 4000 * 3000), 8, RoundingMode.HALF_UP);

        JsonNode noise = analysis(season).get("noise");
        assertThat(noise.get("tradingTurns").asInt()).isEqualTo(2);
        assertThat(noise.get("noiseShare").asString()).isEqualTo(expected.toPlainString());
        assertThat(noise.get("followedBuyNotional").asString()).isEqualTo(Long.toString(followed3));
        assertThat(noise.get("followedSellNotional").asString()).isEqualTo(Long.toString(followed6));
        assertThat(noise.get("tradeNotional").asString()).isEqualTo("7000");
    }

    @Test
    void 출처별_수수료는_합쳐서_성적표의_총_수수료가_되고_독려_몫은_랭킹과_알림뿐이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long brk = assetId(jdbc, "BRK.B");
        BehaviourFixtures.fill(jdbc, season, brk, 2, "BUY", "RANKING", 1, 1000, 10);
        BehaviourFixtures.fill(jdbc, season, brk, 3, "BUY", "ALERT", 2, 1000, 20);
        BehaviourFixtures.fill(jdbc, season, brk, 4, "BUY", "FEED", 3, 1000, 40);
        BehaviourFixtures.fill(jdbc, season, brk, 5, "BUY", "DIRECT", 4, 1000, 80);
        BehaviourFixtures.fill(jdbc, season, brk, 6, "BUY", "RANKING", 1, 1000, 5);
        JsonNode a = analysis(season);
        JsonNode report = report(season);
        AnalysisAssertions.assertConsistent(a, report, 1);
        JsonNode p = a.get("platform");
        JsonNode rows = p.get("bySource");
        assertThat(rows.get(0).get("orders").asInt()).isEqualTo(2);
        assertThat(rows.get(0).get("fee").asString()).isEqualTo("15");
        assertThat(rows.get(0).get("tradeNotional").asString()).isEqualTo("2000");
        assertThat(rows.get(1).get("fee").asString()).as("FEED").isEqualTo("40");
        assertThat(rows.get(2).get("fee").asString()).as("ALERT").isEqualTo("20");
        assertThat(rows.get(3).get("orders").asInt()).as("BENCH").isZero();
        assertThat(rows.get(4).get("fee").asString()).as("DIRECT").isEqualTo("80");
        assertThat(p.get("nudgedFee").asString()).isEqualTo("35");
        assertThat(report.get("platformNudgeFee").asString()).isEqualTo("35");
        BigDecimal total = new BigDecimal(p.get("totalFee").asString());
        assertThat(total).isEqualByComparingTo(new BigDecimal(p.get("teamFee").asString()).add(new BigDecimal("155")));
        assertThat(p.get("nudgedFeeShare").asString())
                .isEqualTo(new BigDecimal("35").divide(total, 8, RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    void 거래가_없는_시즌은_소음_비율이_null이고_수수료는_팀_구성뿐이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        JsonNode a = analysis(season);
        JsonNode noise = a.get("noise");
        assertThat(noise.get("noiseShare").isNull()).isTrue();
        assertThat(noise.get("tradingTurns").asInt()).isZero();
        assertThat(noise.get("tradeNotional").asString()).isEqualTo("0");
        JsonNode p = a.get("platform");
        assertThat(p.get("totalFee").asString()).isEqualTo(p.get("teamFee").asString());
        assertThat(p.get("nudgedFee").asString()).isEqualTo("0");
        assertThat(p.get("nudgedFeeShare").asString()).isEqualTo("0.00000000");
    }
}
