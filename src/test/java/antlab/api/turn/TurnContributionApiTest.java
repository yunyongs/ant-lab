package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.ratio;
import static antlab.api.turn.TurnViewSupport.won;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.BenchmarkToDateDto;
import antlab.scoring.HoldingContributionDto;
import antlab.scoring.PositionContributionDto;
import antlab.scoring.PositionContributionsDto;
import antlab.scoring.TurnContributionDto;
import antlab.support.ExposureCatalog;
import antlab.support.ExposureRules;
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
 * Turn contribution: values equal the stored rows and fills, the change splits into price effect and trading cost, a
 * played turn answers the same later, and the benchmark at the last turn equals the season report's.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnContributionApiTest {

    private static final List<String> POSITIONS = List.of("goalkeeper", "defender", "midfielder", "forward");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse get(long season, String turnNo) {
        return mvc.get().uri("/api/v1/seasons/" + season + "/turns/" + turnNo + "/contribution").exchange().getResponse();
    }

    private JsonNode body(long season, int turnNo) throws Exception {
        MockHttpServletResponse r = get(season, Integer.toString(turnNo));
        assertThat(r.getStatus()).as("turn " + turnNo).isEqualTo(200);
        return json(r);
    }

    /** Team at turn 0, advance to 2, one more buy at turn 2 (so turn 3 has a trading cost), then advance to {@code to}. */
    private long seasonWithTrade(int to) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int turn = 0; turn < to; turn++) {
            if (turn == 2) {
                buy(mvc, jdbc, season, 2, "000660", 1);
            }
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
        }
        return season;
    }

    private static BigDecimal dec(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    @Test
    void every_played_turn_matches_the_stored_rows_and_the_fills() throws Exception {
        long season = seasonWithTrade(5);
        BigDecimal initial = won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season));
        for (int turn = 1; turn <= 5; turn++) {
            JsonNode o = body(season, turn);
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = ?", season, turn);
            Map<String, Object> prev = turn == 1 ? null
                    : jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = ?", season, turn - 1);
            BigDecimal total = won(row.get("total_value"));
            BigDecimal previousTotal = prev == null ? initial : won(prev.get("total_value"));

            assertThat(o.get("turnNo").asInt()).isEqualTo(turn);
            assertThat(o.get("totalValue").asString()).isEqualTo(total.toPlainString());
            assertThat(o.get("previousTotalValue").asString()).isEqualTo(previousTotal.toPlainString());
            assertThat(o.get("valueChange").asString()).isEqualTo(total.subtract(previousTotal).toPlainString());
            assertThat(o.get("totalReturn").asString()).isEqualTo(ratio(total.subtract(initial), initial).toPlainString());
            for (String position : POSITIONS) {
                JsonNode p = o.get("positions").get(position);
                BigDecimal value = won(row.get(position + "_value"));
                BigDecimal before = prev != null ? won(prev.get(position + "_value"))
                        : position.equals("goalkeeper") ? initial : BigDecimal.ZERO;
                assertThat(p.get("value").asString()).as(position).isEqualTo(value.toPlainString());
                assertThat(p.get("previousValue").asString()).as(position).isEqualTo(before.toPlainString());
                assertThat(p.get("valueChange").asString()).as(position).isEqualTo(value.subtract(before).toPlainString());
            }

            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal heldValue = BigDecimal.ZERO;
            for (JsonNode h : o.get("holdings")) {
                long asset = h.get("assetId").asLong();
                int quantity = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE"
                        + " -e.quantity END), 0) FROM orders o JOIN execution e ON e.order_id = o.id"
                        + " WHERE o.season_id = ? AND o.asset_id = ? AND o.turn_no < ?", Integer.class, season, asset, turn);
                BigDecimal p0 = won(jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ?"
                        + " AND turn_no = ?", BigDecimal.class, season, asset, turn - 1));
                BigDecimal p1 = won(jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ?"
                        + " AND turn_no = ?", BigDecimal.class, season, asset, turn));
                BigDecimal contribution = p1.subtract(p0).multiply(BigDecimal.valueOf(quantity));
                assertThat(h.get("quantity").asInt()).isEqualTo(quantity);
                assertThat(h.get("previousPrice").asString()).isEqualTo(p0.toPlainString());
                assertThat(h.get("price").asString()).isEqualTo(p1.toPlainString());
                assertThat(h.get("contribution").asString()).isEqualTo(contribution.toPlainString());
                assertThat(h.get("priceReturn").asString()).isEqualTo(ratio(p1.subtract(p0), p0).toPlainString());
                assertThat(h.get("contributionReturn").asString())
                        .isEqualTo(ratio(contribution, previousTotal).toPlainString());
                sum = sum.add(contribution);
                heldValue = heldValue.add(dec(h.get("value")));
            }
            assertThat(o.get("holdings")).hasSize(4);
            assertThat(heldValue).isEqualByComparingTo(won(row.get("holdings_value")));
            assertThat(o.get("holdingsContribution").asString()).isEqualTo(sum.toPlainString());
            BigDecimal positionSum = BigDecimal.ZERO;
            for (String position : POSITIONS) {
                positionSum = positionSum.add(dec(o.get("positions").get(position).get("contribution")));
            }
            assertThat(positionSum).isEqualByComparingTo(sum);
        }
    }

    @Test
    void the_change_is_the_price_effect_minus_the_fees_of_the_previous_turn() throws Exception {
        long season = seasonWithTrade(4);
        for (int turn = 1; turn <= 4; turn++) {
            JsonNode o = body(season, turn);
            BigDecimal fees = won(jdbc.queryForObject("SELECT COALESCE(sum(e.fee + e.tax), 0) FROM orders o"
                    + " JOIN execution e ON e.order_id = o.id WHERE o.season_id = ? AND o.turn_no = ?",
                    BigDecimal.class, season, turn - 1));
            assertThat(o.get("tradingCost").asString()).as("turn " + turn).isEqualTo(fees.toPlainString());
            assertThat(dec(o.get("valueChange"))).as("turn " + turn)
                    .isEqualByComparingTo(dec(o.get("holdingsContribution")).subtract(fees));
        }
        // Team build fees land on turn 1, the turn-2 buy on turn 3, and nothing was bought at turn 1
        assertThat(dec(body(season, 1).get("tradingCost")).signum()).isPositive();
        assertThat(dec(body(season, 2).get("tradingCost")).signum()).isZero();
        assertThat(dec(body(season, 3).get("tradingCost")).signum()).isPositive();
    }

    @Test
    void holdings_are_sorted_by_contribution_and_carry_the_declared_position() throws Exception {
        long season = seasonWithTrade(3);
        JsonNode holdings = body(season, 3).get("holdings");
        List<BigDecimal> contributions = new ArrayList<>();
        holdings.forEach(h -> contributions.add(dec(h.get("contribution"))));
        assertThat(contributions).isSortedAccordingTo((a, b) -> b.compareTo(a));
        for (JsonNode h : holdings) {
            String declared = jdbc.queryForObject("SELECT declared_position FROM lineup WHERE season_id = ? AND asset_id = ?"
                    + " AND end_turn IS NULL", String.class, season, h.get("assetId").asLong());
            assertThat(h.get("declaredPosition").asString()).isEqualTo(declared);
        }
        // The turn-2 buy of 1 more share is held through the move into turn 3
        long sk = TurnApiSupport.assetId(jdbc, "000660");
        JsonNode bought = null;
        for (JsonNode h : holdings) {
            if (h.get("assetId").asLong() == sk) {
                bought = h;
            }
        }
        assertThat(bought).isNotNull();
        assertThat(bought.get("quantity").asInt()).isEqualTo(11);
    }

    private void order(long season, int turn, String side, String ticker, int qty, String position) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"side\": \"" + side + "\", \"assetId\": "
                + TurnApiSupport.assetId(jdbc, ticker) + ", \"quantity\": " + qty
                + (position == null ? "" : ", \"declaredPosition\": \"" + position + "\"") + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
        assertThat(r.getStatus()).as(side + " " + ticker + " at turn " + turn).isEqualTo(201);
    }

    private void relabel(long season, int turn, String ticker, String position) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"assetId\": " + TurnApiSupport.assetId(jdbc, ticker)
                + ", \"declaredPosition\": \"" + position + "\"}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/lineup-changes")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
        assertThat(r.getStatus()).as("relabel " + ticker + " at turn " + turn).isEqualTo(200);
    }

    private static JsonNode holding(JsonNode body, long assetId) {
        for (JsonNode h : body.get("holdings")) {
            if (h.get("assetId").asLong() == assetId) {
                return h;
            }
        }
        return null;
    }

    /** Stored-row equality and the identity for one turn (the service would answer 500 if either broke). */
    private void assertConsistent(long season, int turn, JsonNode o) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM turn WHERE season_id = ? AND turn_no = ?", season, turn);
        assertThat(o.get("totalValue").asString()).isEqualTo(won(row.get("total_value")).toPlainString());
        for (String position : POSITIONS) {
            assertThat(o.get("positions").get(position).get("value").asString()).as(position)
                    .isEqualTo(won(row.get(position + "_value")).toPlainString());
        }
        BigDecimal held = BigDecimal.ZERO;
        for (JsonNode h : o.get("holdings")) {
            held = held.add(dec(h.get("value")));
        }
        assertThat(held).isEqualByComparingTo(won(row.get("holdings_value")));
        assertThat(dec(o.get("valueChange")))
                .isEqualByComparingTo(dec(o.get("holdingsContribution")).subtract(dec(o.get("tradingCost"))));
    }

    @Test
    void sells_a_full_sell_with_re_buy_and_lineup_changes_keep_the_turn_consistent() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        // Turn 2 (= k - 1): partial sell, full sell then re-buy under another position, and a relabel
        order(season, 2, "SELL", "069500", 40, null);
        order(season, 2, "SELL", "SPY", 5, null);
        order(season, 2, "BUY", "SPY", 2, "MIDFIELDER");
        relabel(season, 2, "000660", "FORWARD_TARGET");
        assertThat(advance(mvc, season, 2).getStatus()).isEqualTo(201);

        JsonNode turn3 = body(season, 3);
        String turn3Text = get(season, "3").getContentAsString();
        assertThat(holding(turn3, TurnApiSupport.assetId(jdbc, "069500")).get("quantity").asInt()).isEqualTo(60);
        JsonNode spy = holding(turn3, TurnApiSupport.assetId(jdbc, "SPY"));
        assertThat(spy.get("quantity").asInt()).isEqualTo(2);
        assertThat(spy.get("declaredPosition").asString()).isEqualTo("MIDFIELDER");
        assertThat(holding(turn3, TurnApiSupport.assetId(jdbc, "000660")).get("declaredPosition").asString())
                .isEqualTo("FORWARD_TARGET");
        assertThat(dec(turn3.get("tradingCost")).signum()).isPositive();

        // Turn 3 (= k): a relabel and a full sell that closes a span during the turn must not change turn 3
        relabel(season, 3, "BRK.B", "DEFENDER");
        order(season, 3, "SELL", "000660", 10, null);
        assertThat(get(season, "3").getContentAsString()).isEqualTo(turn3Text);
        assertThat(advance(mvc, season, 3).getStatus()).isEqualTo(201);
        assertThat(get(season, "3").getContentAsString()).isEqualTo(turn3Text);

        JsonNode turn4 = body(season, 4);
        assertThat(holding(turn4, TurnApiSupport.assetId(jdbc, "000660"))).isNull();
        assertThat(holding(turn4, TurnApiSupport.assetId(jdbc, "BRK.B")).get("declaredPosition").asString())
                .isEqualTo("DEFENDER");
        for (int turn = 2; turn <= 4; turn++) {
            assertConsistent(season, turn, body(season, turn));
        }
    }

    @Test
    void a_played_turn_answers_the_same_after_later_turns() throws Exception {
        long season = seasonWithTrade(2);
        String early = get(season, "2").getContentAsString();
        buy(mvc, jdbc, season, 2, "000660", 1);
        assertThat(advance(mvc, season, 2).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 3).getStatus()).isEqualTo(201);
        assertThat(get(season, "2").getContentAsString()).isEqualTo(early);
    }

    @Test
    void the_benchmark_at_the_last_turn_equals_the_season_report() throws Exception {
        long season = seasonWithTrade(12);
        JsonNode report = json(mvc.get().uri("/api/v1/seasons/" + season + "/report").exchange().getResponse());
        JsonNode o = body(season, 12);
        JsonNode b = o.get("benchmark");
        assertThat(b.get("value").asString()).isEqualTo(report.get("baselineFinalValue").asString());
        assertThat(b.get("totalReturn").asString()).isEqualTo(report.get("baselineReturn").asString());
        assertThat(b.get("excessReturn").asString()).isEqualTo(report.get("excessReturn").asString());
        assertThat(o.get("totalReturn").asString()).isEqualTo(report.get("totalReturn").asString());
        assertThat(o.get("totalValue").asString()).isEqualTo(report.get("finalValue").asString());
    }

    @Test
    void the_benchmark_chains_turn_by_turn_from_the_initial_capital() throws Exception {
        long season = seasonWithTrade(4);
        String initial = won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season))
                .toPlainString();
        String previous = initial;
        for (int turn = 1; turn <= 4; turn++) {
            JsonNode b = body(season, turn).get("benchmark");
            assertThat(b.get("previousValue").asString()).as("turn " + turn).isEqualTo(previous);
            BigDecimal value = dec(b.get("value"));
            BigDecimal before = new BigDecimal(previous);
            assertThat(b.get("valueChange").asString()).isEqualTo(value.subtract(before).toPlainString());
            assertThat(b.get("periodReturn").asString()).isEqualTo(ratio(value.subtract(before), before).toPlainString());
            previous = b.get("value").asString();
        }
    }

    @Test
    void a_future_turn_is_409_and_a_bad_number_is_400_and_an_unknown_season_is_404() throws Exception {
        long season = seasonWithTrade(2);
        MockHttpServletResponse future = get(season, "3");
        assertThat(future.getStatus()).isEqualTo(409);
        assertThat(code(future)).isEqualTo("TURN_NOT_REACHED");
        assertThat(get(season, "0").getStatus()).isEqualTo(400);
        assertThat(get(season, "abc").getStatus()).isEqualTo(400);
        assertThat(get(999_999_999L, "1").getStatus()).isEqualTo(404);
    }

    @Test
    void the_body_keys_are_the_record_components_and_carry_no_date() throws Exception {
        long season = seasonWithTrade(3);
        MockHttpServletResponse r = get(season, "3");
        JsonNode o = json(r);
        assertThat(o.propertyNames()).containsExactly(names(TurnContributionDto.class));
        assertThat(o.get("positions").propertyNames()).containsExactly(names(PositionContributionsDto.class));
        assertThat(o.get("positions").get("defender").propertyNames()).containsExactly(names(PositionContributionDto.class));
        assertThat(o.get("holdings").get(0).propertyNames()).containsExactly(names(HoldingContributionDto.class));
        assertThat(o.get("benchmark").propertyNames()).containsExactly(names(BenchmarkToDateDto.class));
        assertThat(r.getContentAsString()).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}")
                .doesNotContain("month", "Month", "block", "seed", "date", "Date");
    }

    @Test
    void the_records_come_from_exposed_columns() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        for (Class<?> record : List.of(TurnContributionDto.class, PositionContributionsDto.class,
                PositionContributionDto.class, HoldingContributionDto.class, BenchmarkToDateDto.class)) {
            assertThat(ExposureRules.check(record, catalog)).as(record.getSimpleName()).isEmpty();
        }
    }

    private static String[] names(Class<?> record) {
        return java.util.Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
    }
}
