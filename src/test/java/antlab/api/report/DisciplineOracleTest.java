package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.DisciplineCalculator;
import antlab.scoring.DisciplineCalculator.Result;
import antlab.scoring.DisciplineFacts;
import antlab.scoring.DisciplineFacts.ChangeFact;
import antlab.scoring.DisciplineFacts.OrderFact;
import antlab.scoring.DisciplineFacts.TurnValue;
import antlab.scoring.DisciplineQuery;
import antlab.scoring.SeasonReportQuery;
import antlab.season.FormationChangeRepository;
import antlab.season.TurnRepository;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The discipline source query and calculator against an independent SQL oracle ({@code scoring/discipline_oracle.sql}) on a
 * finished 12-turn season of the synthetic fixture. Orders are written straight into the tables (every pool asset, both
 * sides, every turn 1..11, some orders in two fills, some bound to a formation change) and a few turn snapshots are
 * rewritten so that the team falls 10 % and exactly 8 % at chosen turns; the report endpoint is not called.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class DisciplineOracleTest {

    private static final int LAST_TURN = 12;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JdbcClient client;
    @Autowired
    DisciplineQuery query;
    @Autowired
    SeasonReportQuery reportQuery;
    @Autowired
    TurnRepository turns;
    @Autowired
    FormationChangeRepository changeRepository;

    private static String oracleSql() throws Exception {
        return new ClassPathResource("scoring/discipline_oracle.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> oracle(long season) throws Exception {
        return client.sql(oracleSql()).param("seasonId", season).query().singleRow();
    }

    /** A turn snapshot that holds only cash: it satisfies every snapshot constraint and sets the team value exactly. */
    private void setTeamValue(long season, int turn, long value) {
        int rows = jdbc.update("UPDATE turn SET cash_balance = ?, holdings_value = 0, total_value = ?, goalkeeper_value = ?, "
                + "defender_value = 0, midfielder_value = 0, forward_value = 0 WHERE season_id = ? AND turn_no = ?",
                value, value, value, season, turn);
        assertThat(rows).isEqualTo(1);
    }

    private long teamValue(long season, int turn) {
        return jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = ?", Long.class, season, turn);
    }

    private long change(long season, int turn, int[] before, int[] after) {
        return jdbc.queryForObject("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, "
                + "before_midfielder, before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, season, turn, before[0], before[1],
                before[2], before[3], after[0], after[1], after[2], after[3]);
    }

    private long price(long season, long asset, int turn) {
        return jdbc.queryForObject("SELECT price FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = ?",
                Long.class, season, asset, turn);
    }

    private void fill(long season, long asset, int turn, String side, int quantity, Long changeId) {
        Long order = jdbc.queryForObject("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, "
                + "formation_change_id) VALUES (?, ?, ?, ?, ?, 'FILLED', 'DIRECT', ?) RETURNING id", Long.class, season,
                asset, turn, side, quantity, changeId);
        long fillPrice = price(season, asset, turn);
        int firstPart = quantity >= 2 ? 1 : quantity;
        jdbc.update("INSERT INTO execution (order_id, fill_price, quantity) VALUES (?, ?, ?)", order, fillPrice, firstPart);
        if (quantity > firstPart) {
            jdbc.update("INSERT INTO execution (order_id, fill_price, quantity) VALUES (?, ?, ?)", order, fillPrice,
                    quantity - firstPart);
        }
    }

    /** Team falls: turn 3 by 10 %, turn 7 by 10 %, turn 9 by exactly 8 %; changes at those turns; orders everywhere. */
    private long seed() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        setTeamValue(season, 3, teamValue(season, 2) * 90 / 100);
        setTeamValue(season, 7, teamValue(season, 6) * 90 / 100);
        setTeamValue(season, 8, 10_000_000);
        setTeamValue(season, 9, 9_200_000);
        long c3 = change(season, 3, new int[] {20, 30, 30, 20}, new int[] {20, 40, 30, 10});
        long c7 = change(season, 7, new int[] {20, 40, 30, 10}, new int[] {20, 20, 30, 30});
        long c9a = change(season, 9, new int[] {20, 20, 30, 30}, new int[] {20, 15, 30, 35});
        change(season, 9, new int[] {20, 15, 30, 35}, new int[] {20, 45, 30, 5});
        Map<Integer, Long> firstChange = Map.of(3, c3, 7, c7, 9, c9a);
        List<Long> assets = List.of(assetId(jdbc, "000660"), assetId(jdbc, "069500"), assetId(jdbc, "SPY"),
                assetId(jdbc, "BRK.B"));
        for (int turn = 1; turn < LAST_TURN; turn++) {
            for (int i = 0; i < assets.size(); i++) {
                int quantity = 1 + (i + turn) % 4;
                // the first asset's sell of a change turn is bound to that turn's first change
                fill(season, assets.get(i), turn, "SELL", quantity, i == 0 ? firstChange.get(turn) : null);
                fill(season, assets.get(i), turn, "BUY", quantity, null);
            }
        }
        // a fill at the last turn T is outside the judged range (the report's range is turn_no < T as well)
        fill(season, assets.get(0), LAST_TURN, "BUY", 2, null);
        // an order without any fill is not part of the discipline amounts
        jdbc.update("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source) "
                + "VALUES (?, ?, 2, 'SELL', 1, 'CANCELED', 'DIRECT')", season, assets.get(0));
        return season;
    }

    private DisciplineFacts facts(long season) {
        List<TurnValue> values = turns.findBySeasonIdAndTurnNoBetween(season, 1, LAST_TURN).stream()
                .map(t -> new TurnValue(t.getTurnNo(), t.getTotalValue())).toList();
        List<ChangeFact> changes = changeRepository.findAllBySeasonId(season).stream()
                .map(c -> new ChangeFact(c.getId(), c.getTurnNo(), c.getBefore().forward(), c.getAfter().forward())).toList();
        BigDecimal initial = jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season);
        return new DisciplineFacts(LAST_TURN, initial, values, changes, query.orders(season, LAST_TURN));
    }

    @Test
    void java_calculator_equals_the_sql_oracle_and_the_report_trade_amount() throws Exception {
        long season = seed();
        Map<String, Object> o = oracle(season);
        Result r = DisciplineCalculator.calculate(facts(season));

        assertThat(r.sellNotional()).isEqualByComparingTo((BigDecimal) o.get("sell_notional"));
        assertThat(r.panicSellNotional()).isEqualByComparingTo((BigDecimal) o.get("panic_sell_notional"));
        assertThat(r.buyNotional()).isEqualByComparingTo((BigDecimal) o.get("buy_notional"));
        assertThat(r.chaseBuyNotional()).isEqualByComparingTo((BigDecimal) o.get("chase_buy_notional"));
        assertThat(r.tradeNotional()).isEqualByComparingTo((BigDecimal) o.get("trade_notional"));
        assertThat(r.formationChanges()).isEqualTo(((Number) o.get("formation_changes")).intValue());
        assertThat(r.panicFormationChanges()).isEqualTo(((Number) o.get("panic_formation_changes")).intValue());

        // the fixture must reach every branch, otherwise equal numbers prove little
        assertThat(r.panicSellNotional().signum()).as("panic sells exist").isPositive();
        assertThat(r.panicSellNotional()).as("not every sell is panic").isLessThan(r.sellNotional());
        assertThat(r.chaseBuyNotional().signum()).as("chase buys exist").isPositive();
        assertThat(r.chaseBuyNotional()).as("not every buy is chase").isLessThan(r.buyNotional());
        assertThat(r.formationChanges()).isEqualTo(4);
        // turn 3 (one row) and turn 9 (two rows, exactly -8 %): panic; turn 7 fell too but the forward target rose
        assertThat(r.panicFormationChanges()).isEqualTo(3);

        // the report's own trade amount over the same orders
        assertThat(r.tradeNotional()).isEqualByComparingTo(reportQuery.orders(season, LAST_TURN).tradeNotional());
    }

    @Test
    void query_returns_only_filled_orders_of_turns_one_to_t_minus_one_in_turn_order() throws Exception {
        long season = seed();
        List<OrderFact> rows = query.orders(season, LAST_TURN);
        int expected = jdbc.queryForObject("SELECT count(*) FROM orders o WHERE o.season_id = ? AND o.turn_no BETWEEN 1 AND 11 "
                + "AND EXISTS (SELECT 1 FROM execution e WHERE e.order_id = o.id)", Integer.class, season);
        assertThat(rows).hasSize(expected);
        assertThat(rows).hasSize(4 * 2 * 11);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.turnNo()).isBetween(1, 11);
            assertThat(r.price()).isNotNull();
            assertThat(r.previousPrice()).isNotNull();
        });
        assertThat(rows).extracting(OrderFact::turnNo).isSorted();
        // turn 0 (team building) and the unfilled order exist in the tables but not in the rows
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ? AND turn_no = 0", Integer.class, season))
                .isPositive();
        // a fill in two parts is one order with the summed amount
        OrderFact twoParts = rows.stream().filter(r -> r.turnNo() == 1 && r.asset().ticker().equals("SPY")).findFirst().orElseThrow();
        BigDecimal summed = jdbc.queryForObject("SELECT sum(e.fill_price * e.quantity) FROM execution e WHERE e.order_id = ?",
                BigDecimal.class, twoParts.orderId());
        assertThat(twoParts.notional()).isEqualByComparingTo(summed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution WHERE order_id = ?", Integer.class, twoParts.orderId()))
                .isEqualTo(2);
    }

    @Test
    void changing_prices_after_turn_t_leaves_the_rows_up_to_t_unchanged() throws Exception {
        long season = seed();
        List<OrderFact> before = query.orders(season, LAST_TURN);
        jdbc.update("UPDATE turn_price SET price = price * 2 WHERE season_id = ? AND turn_no > 5", season);
        List<OrderFact> after = query.orders(season, LAST_TURN);
        assertThat(after.stream().filter(r -> r.turnNo() <= 5).toList())
                .isEqualTo(before.stream().filter(r -> r.turnNo() <= 5).toList());
        // turn 6 compares with turn 5, so its previous price is untouched; its own price moved
        assertThat(after.stream().filter(r -> r.turnNo() == 6).toList())
                .isNotEqualTo(before.stream().filter(r -> r.turnNo() == 6).toList());
    }
    @Test
    void a_missing_turn_price_keeps_the_order_with_a_null_price_and_the_calculator_refuses() throws Exception {
        long season = seed();
        long asset = assetId(jdbc, "000660");
        int before = query.orders(season, LAST_TURN).size();
        // turn 5 price gone: the orders of turn 5 lose the current price, the orders of turn 6 lose the previous price
        assertThat(jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = 5", season, asset))
                .isEqualTo(1);
        List<OrderFact> rows = query.orders(season, LAST_TURN);
        assertThat(rows).as("no order may disappear with its price").hasSize(before);
        assertThat(rows.stream().filter(r -> r.turnNo() == 5 && r.asset().ticker().equals("000660")))
                .hasSize(2).allSatisfy(r -> {
                    assertThat(r.price()).isNull();
                    assertThat(r.notional().signum()).isPositive();
                });
        assertThat(rows.stream().filter(r -> r.turnNo() == 6 && r.asset().ticker().equals("000660")))
                .hasSize(2).allSatisfy(r -> assertThat(r.previousPrice()).isNull());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DisciplineCalculator.calculate(facts(season)))
                .isInstanceOf(IllegalStateException.class);
    }
}
