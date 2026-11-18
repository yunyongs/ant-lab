package antlab.api.report;

import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.DefenderGoalCalculator.HeldTurn;
import antlab.scoring.DefenderHoldingQuery;
import antlab.scoring.SeasonSignalReader;
import antlab.scoring.TurnSignals;
import antlab.season.LineupChangeService;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * The review facts of the season analysis (MVP-19 PR B) on the synthetic fixture: attention following against an oracle built from
 * the stored signals and prices, defender goals on hand-set prices and two position-only lineup changes, and the blocks of an
 * abandoned season. The fixture pool has only the market baselines, so the change effects are null here (they are hand-checked
 * in the calculator test).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReviewFactsApiTest {

    private static final int TURNS = 12;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonSignalReader signalReader;
    @Autowired
    LineupChangeService lineupChanges;
    @Autowired
    DefenderHoldingQuery heldQuery;

    private JsonNode analysis(long season) throws Exception {
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private JsonNode report(long season) throws Exception {
        return json(ReportApiSupport.getReport(mvc, season));
    }

    private BigDecimal price(long season, long asset, int turn) {
        return jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = ?",
                BigDecimal.class, season, asset, turn);
    }

    private void setPrices(long season, String ticker, long... byTurn) {
        long asset = assetId(jdbc, ticker);
        for (int turn = 0; turn < byTurn.length; turn++) {
            assertThat(jdbc.update("UPDATE turn_price SET price = ? WHERE season_id = ? AND asset_id = ? AND turn_no = ?", byTurn[turn],
                    season, asset, turn)).isEqualTo(1);
        }
    }

    private static long[] steps(int turns, long before, int changeTurn, long after) {
        long[] out = new long[turns + 1];
        for (int t = 0; t <= turns; t++) {
            out[t] = t < changeTurn ? before : after;
        }
        return out;
    }

    private record Buy(long asset, int turn, int quantity, long fillPrice) {
        long notional() {
            return quantity * fillPrice;
        }
    }

    @Test
    void 관심_추종은_저장된_신호와_가격으로_만든_정답과_같다() throws Exception {
        long season = playedSeason(mvc, jdbc, TURNS);
        List<TurnSignals> signals = signalReader.read(season, TURNS);
        List<Long> pool = jdbc.queryForList("SELECT asset_id FROM turn_price WHERE season_id = ? AND turn_no = 0 ORDER BY asset_id",
                Long.class, season);
        int topTurn = -1;
        for (TurnSignals s : signals) {
            if (!s.topAttention().isEmpty() && s.turnNo() <= 3) {
                topTurn = s.turnNo();
                break;
            }
        }
        assertThat(topTurn).as("the seeded attention puts something in the top grade early").isPositive();
        long top = signals.get(topTurn - 1).topAttention().iterator().next();
        long plain = pool.stream().filter(a -> !signals.get(1).topAttention().contains(a)).findFirst().orElseThrow();
        long plainAtSix = pool.stream().filter(a -> !signals.get(5).topAttention().contains(a)).findFirst().orElseThrow();
        List<Buy> buys = List.of(new Buy(top, topTurn, 3, 1000), new Buy(plain, 2, 2, 2000), new Buy(plainAtSix, 6, 5, 1000),
                new Buy(top, 8, 1, 1000), new Buy(plain, 11, 1, 1000));
        for (Buy b : buys) {
            BehaviourFixtures.fill(jdbc, season, b.asset, b.turn, "BUY", "DIRECT", b.quantity, b.fillPrice, 0);
        }

        BigDecimal[] weighted = {BigDecimal.ZERO, BigDecimal.ZERO};
        long[] notional = new long[2];
        int[] count = new int[2];
        int incomplete = 0;
        for (Buy b : buys) {
            if (b.turn + 6 > TURNS) {
                incomplete++;
                continue;
            }
            int group = signals.get(b.turn - 1).topAttention().contains(b.asset) ? 0 : 1;
            BigDecimal change = price(season, b.asset, b.turn + 6).divide(price(season, b.asset, b.turn), new MathContext(60))
                    .subtract(BigDecimal.ONE);
            weighted[group] = weighted[group].add(change.multiply(BigDecimal.valueOf(b.notional())));
            notional[group] += b.notional();
            count[group]++;
        }
        assertThat(count[0]).as("not vacuous: an attention buy").isEqualTo(1);
        assertThat(count[1]).as("not vacuous: two other buys").isEqualTo(2);
        assertThat(incomplete).isEqualTo(2);

        JsonNode a = analysis(season);
        AnalysisAssertions.assertConsistent(a, report(season), 1);
        JsonNode att = a.get("attentionFollowing");
        assertThat(att.get("windowTurns").asInt()).isEqualTo(6);
        assertThat(att.get("attentionBuys").asInt()).isEqualTo(count[0]);
        assertThat(att.get("attentionBuyNotional").asString()).isEqualTo(Long.toString(notional[0]));
        assertThat(att.get("attentionReturn").asString()).isEqualTo(
                weighted[0].divide(BigDecimal.valueOf(notional[0]), new MathContext(60)).setScale(8, RoundingMode.HALF_UP).toPlainString());
        assertThat(att.get("otherBuys").asInt()).isEqualTo(count[1]);
        assertThat(att.get("otherBuyNotional").asString()).isEqualTo(Long.toString(notional[1]));
        assertThat(att.get("otherReturn").asString()).isEqualTo(
                weighted[1].divide(BigDecimal.valueOf(notional[1]), new MathContext(60)).setScale(8, RoundingMode.HALF_UP).toPlainString());
        assertThat(att.get("incompleteBuys").asInt()).isEqualTo(incomplete);
    }

    /**
     * SPY (declared defender) is changed to midfielder at turn 3 and BRK.B (declared goalkeeper) to a forward at turn 4, in the turn
     * where the market pair falls (069500 halves, SPY rises). The goal of the move into turn 4 is judged by the position declared as
     * of turn 3: BRK.B still counts, SPY no longer does.
     */
    @Test
    void 수비수_득점은_직전_턴_기준_선언으로_판정하고_포지션_단독_변경을_따른다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 3; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        lineupChanges.change(season, 3, assetId(jdbc, "SPY"), Position.MIDFIELDER);
        assertThat(advance(mvc, season, 3).getStatus()).isEqualTo(201);
        lineupChanges.change(season, 4, assetId(jdbc, "BRK.B"), Position.FORWARD_TARGET);
        for (int t = 4; t < TURNS; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        // a buy of the turn itself is not yet held at its start: 3 more BRK.B shares bought at turn 4 do not change the goal of turn 4
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "BRK.B"), 4, "BUY", 3);
        long[] kr = steps(TURNS, 10_000, 99, 10_000);
        kr[4] = 5_000;
        setPrices(season, "069500", kr);
        setPrices(season, "SPY", steps(TURNS, 20_000, 4, 22_000));
        setPrices(season, "BRK.B", steps(TURNS, 100_000, 4, 110_000));
        setPrices(season, "000660", steps(TURNS, 50_000, 4, 60_000));

        JsonNode a = analysis(season);
        JsonNode report = report(season);
        AnalysisAssertions.assertConsistent(a, report, 1);
        JsonNode d = a.get("defenderGoals");
        assertThat(d.get("downMarketTurns").asInt()).as("only turn 4: the pair falls, the rebound at turn 5 is up").isEqualTo(1);
        assertThat(d.get("goals").asInt()).as("BRK.B (goalkeeper as of turn 3); SPY is a midfielder by then").isEqualTo(1);
        assertThat(d.get("goalWon").asString()).as("5 shares x 10,000").isEqualTo("50000");
        assertThat(d.get("goalReturn").asString()).isEqualTo(new BigDecimal("50000")
                .divide(new BigDecimal(report.get("initialCash").asString()), 8, RoundingMode.HALF_UP).toPlainString());
        assertThat(a.get("changeEffects").isNull()).as("the fixture pool has no formation baselines").isTrue();
    }

    /** Only one leg of the pair moves in each down turn, so a wrong leg or weight in the wiring changes the count. */
    @Test
    void 시장_두_종목_중_하나만_내려도_내린_턴이고_한쪽만_오르면_아니다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        long[] kr = new long[TURNS + 1];
        long[] us = new long[TURNS + 1];
        long[] gold = new long[TURNS + 1];
        for (int t = 0; t <= TURNS; t++) {
            kr[t] = t < 6 ? 10_000 : 9_000;
            us[t] = t < 4 ? 20_000 : 18_000;
            gold[t] = 100_000 + 10_000L * t;
        }
        setPrices(season, "069500", kr);
        setPrices(season, "SPY", us);
        setPrices(season, "BRK.B", gold);
        JsonNode d = analysis(season).get("defenderGoals");
        assertThat(d.get("downMarketTurns").asInt()).as("turn 4: only SPY falls; turn 6: only 069500 falls").isEqualTo(2);
        assertThat(d.get("goals").asInt()).as("the rising goalkeeper in both; the flat or falling defender in neither").isEqualTo(2);
        assertThat(d.get("goalWon").asString()).as("2 turns x 5 shares x 10,000").isEqualTo("100000");

        long[] upOnly = new long[TURNS + 1];
        for (int t = 0; t <= TURNS; t++) {
            kr[t] = 10_000;
            upOnly[t] = 20_000 + (t >= 4 ? 2_000 : 0);
        }
        setPrices(season, "069500", kr);
        setPrices(season, "SPY", upOnly);
        assertThat(analysis(season).get("defenderGoals").get("downMarketTurns").asInt()).as("one leg up, the other flat").isZero();
    }

    @Test
    void 선언_구간이_하나가_아니거나_없으면_원천을_거절한다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < TURNS; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        List<HeldTurn> held = heldQuery.held(season, TURNS);
        assertThat(held).as("4 holdings over 12 turns").hasSize(4 * TURNS);
        long spy = assetId(jdbc, "SPY");
        jdbc.update("INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn) VALUES (?, ?, 'FORWARD_TARGET', 0, 12)",
                season, spy);
        assertThatThrownBy(() -> heldQuery.held(season, TURNS)).isInstanceOf(IllegalStateException.class);
        jdbc.update("DELETE FROM lineup WHERE season_id = ? AND asset_id = ? AND declared_position = 'FORWARD_TARGET'", season, spy);
        jdbc.update("DELETE FROM lineup WHERE season_id = ? AND asset_id = ?", season, spy);
        assertThatThrownBy(() -> heldQuery.held(season, TURNS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 포기한_시즌도_세_블록이_나오고_0턴_포기는_빈_값과_영이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 4; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        assertThat(abandon(season, 4).getStatus()).isEqualTo(200);
        JsonNode a = analysis(season);
        AnalysisAssertions.assertConsistent(a, report(season), 1);
        assertThat(a.get("attentionFollowing").get("attentionReturn").isNull()).isTrue();
        assertThat(a.get("attentionFollowing").get("incompleteBuys").asInt()).isZero();
        assertThat(a.get("defenderGoals").get("downMarketTurns").asInt()).isBetween(0, TURNS);

        long untouched = startSeason(mvc);
        assertThat(abandon(untouched, 0).getStatus()).isEqualTo(200);
        JsonNode empty = analysis(untouched);
        JsonNode att = empty.get("attentionFollowing");
        assertThat(att.get("attentionBuys").asInt()).isZero();
        assertThat(att.get("otherBuys").asInt()).isZero();
        assertThat(att.get("attentionReturn").isNull()).isTrue();
        assertThat(att.get("otherReturn").isNull()).isTrue();
        assertThat(att.get("attentionBuyNotional").asString()).isEqualTo("0");
        JsonNode d = empty.get("defenderGoals");
        assertThat(d.get("goals").asInt()).isZero();
        assertThat(d.get("goalWon").asString()).isEqualTo("0");
        assertThat(d.get("goalReturn").asString()).isEqualTo("0.00000000");
        assertThat(empty.get("changeEffects").isNull()).isTrue();
    }

    private MockHttpServletResponse abandon(long season, int expectedTurn) {
        return mvc.post().uri("/api/v1/seasons/" + season + "/abandon").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTurnNo\": " + expectedTurn + "}").exchange().getResponse();
    }
}
