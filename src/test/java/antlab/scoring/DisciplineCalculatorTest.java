package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.OrderSide;
import antlab.scoring.DisciplineCalculator.Result;
import antlab.scoring.DisciplineFacts.ChangeFact;
import antlab.scoring.DisciplineFacts.OrderFact;
import antlab.scoring.DisciplineFacts.TurnValue;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed fixture of the trading-discipline item: T = 6, three assets, team values chosen around the 8 % line.
 *
 * <pre>
 * team value V0 30,000,000 (initial cash) V1 30,300,000  V2 27,876,000 (= V1 x 0.92 exactly: drop, boundary)
 *            V3 25,645,921 (V2 x 0.92 = 25,645,920, one won above: no drop)  V4 23,594,247 (drop)  V5 24,000,000  V6 24,100,000
 * changes    c1 t2 fwd 30 to 20 (drop turn, lowered: PANIC)   c2 t3 20 to 10 (no drop turn)
 *            c3 t4 10 to 5, c4 t4 5 to 40 (drop turn, net raised)   c5 t5 40 to 30 (no drop turn)
 *            c9 t0 and c10 t6 are outside turns 1..5 and ignored
 * orders     id turn side asset  won        bound price prev   verdict
 *            1  1    BUY  A     1,000,000  -    108   100    +8.00 % exactly: chase
 *            2  1    BUY  B     2,000,000  -    10799 10000  +7.99 %: no
 *            3  2    SELL A     3,000,000  -    92    100    -8.00 % exactly: panic
 *            4  2    SELL B     1,500,000  -    9201  10000  -7.99 %: no
 *            5  2    SELL C       500,000  c1   105   100    asset rose, bound to the panic change: panic
 *            6  3    SELL A       700,000  c2   100   100    bound to a change of a calm turn: no
 *            7  3    BUY  C       400,000  -    112   100    +12 %: chase
 *            8  4    SELL B       600,000  -    9000  10000  -10 %: panic
 *            9  4    SELL C       250,000  -    100   100    team fell this turn but the asset did not: no
 *            10 4    SELL A       150,000  c3   100   100    bound to a change of a turn whose net target rose: no
 *            11 5    BUY  A       800,000  -    130   100    +30 %: chase
 *            12 5    BUY  B       900,000  -    9500  10000  fall: not a chase
 *            13 0    BUY  A    50,000,000  -    200   100    turn 0 (team building): never judged
 * sells 6,700,000 (panic 4,100,000)  buys 5,100,000 (chase 2,200,000)  trade 11,800,000
 * panic share 4.1/6.7 = 0.61194030  chase share 2.2/5.1 = 0.43137255  undisciplined 6.3/11.8 = 0.53389831
 * </pre>
 */
class DisciplineCalculatorTest {

    /** Every message is a fixed sentence: no turn, amount or id may leak into it. */
    private static final Pattern LEAK = Pattern.compile("\\d");

    private static final AssetKey A = new AssetKey("KR", "AAA001");
    private static final AssetKey B = new AssetKey("US", "BBB002");
    private static final AssetKey C = new AssetKey("KR", "CCC003");

    static BigDecimal won(long v) {
        return new BigDecimal(v).setScale(4);
    }

    static OrderFact order(long id, int turn, OrderSide side, AssetKey asset, long notional, Long change, long price,
            long previous) {
        return new OrderFact(id, turn, side, asset, won(notional), change, won(price), won(previous));
    }

    static List<TurnValue> values(long... v) {
        List<TurnValue> out = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            out.add(new TurnValue(i + 1, won(v[i])));
        }
        return out;
    }

    static List<ChangeFact> changes() {
        return List.of(new ChangeFact(1, 2, 30, 20), new ChangeFact(2, 3, 20, 10), new ChangeFact(3, 4, 10, 5),
                new ChangeFact(4, 4, 5, 40), new ChangeFact(5, 5, 40, 30), new ChangeFact(9, 0, 30, 30),
                new ChangeFact(10, 6, 30, 30));
    }

    static List<OrderFact> orders() {
        return List.of(
                order(1, 1, OrderSide.BUY, A, 1_000_000, null, 108, 100),
                order(2, 1, OrderSide.BUY, B, 2_000_000, null, 10_799, 10_000),
                order(3, 2, OrderSide.SELL, A, 3_000_000, null, 92, 100),
                order(4, 2, OrderSide.SELL, B, 1_500_000, null, 9_201, 10_000),
                order(5, 2, OrderSide.SELL, C, 500_000, 1L, 105, 100),
                order(6, 3, OrderSide.SELL, A, 700_000, 2L, 100, 100),
                order(7, 3, OrderSide.BUY, C, 400_000, null, 112, 100),
                order(8, 4, OrderSide.SELL, B, 600_000, null, 9_000, 10_000),
                order(9, 4, OrderSide.SELL, C, 250_000, null, 100, 100),
                order(10, 4, OrderSide.SELL, A, 150_000, 3L, 100, 100),
                order(11, 5, OrderSide.BUY, A, 800_000, null, 130, 100),
                order(12, 5, OrderSide.BUY, B, 900_000, null, 9_500, 10_000),
                order(13, 0, OrderSide.BUY, A, 50_000_000, null, 200, 100));
    }

    static DisciplineFacts golden() {
        return new DisciplineFacts(6, won(30_000_000),
                values(30_300_000, 27_876_000, 25_645_921, 23_594_247, 24_000_000, 24_100_000), changes(), orders());
    }

    private static void rejected(DisciplineFacts facts) {
        assertThatThrownBy(() -> DisciplineCalculator.calculate(facts))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(LEAK.matcher(e.getMessage()).find()).as("message carries a value").isFalse());
    }

    private static DisciplineFacts with(DisciplineFacts f, List<TurnValue> values, List<ChangeFact> changes,
            List<OrderFact> orders) {
        return new DisciplineFacts(f.reportTurn(), f.initialCash(), values, changes, orders);
    }

    @Test
    void golden_fixture_matches_the_hand_computation() {
        Result r = DisciplineCalculator.calculate(golden());
        assertThat(r.sellNotional().toPlainString()).isEqualTo("6700000");
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("4100000");
        assertThat(r.panicSellShare().toPlainString()).isEqualTo("0.61194030");
        assertThat(r.buyNotional().toPlainString()).isEqualTo("5100000");
        assertThat(r.chaseBuyNotional().toPlainString()).isEqualTo("2200000");
        assertThat(r.chaseBuyShare().toPlainString()).isEqualTo("0.43137255");
        assertThat(r.tradeNotional().toPlainString()).isEqualTo("11800000");
        assertThat(r.undisciplinedShare().toPlainString()).isEqualTo("0.53389831");
        assertThat(r.formationChanges()).isEqualTo(5);
        assertThat(r.panicFormationChanges()).isEqualTo(1);
    }

    @Test
    void input_order_of_changes_and_orders_does_not_matter() {
        DisciplineFacts g = golden();
        List<ChangeFact> reversedChanges = new ArrayList<>(g.changes());
        java.util.Collections.reverse(reversedChanges);
        List<OrderFact> reversedOrders = new ArrayList<>(g.orders());
        java.util.Collections.reverse(reversedOrders);
        assertThat(DisciplineCalculator.calculate(with(g, g.turnValues(), reversedChanges, reversedOrders)))
                .isEqualTo(DisciplineCalculator.calculate(g));
    }

    @Test
    void asset_boundary_is_inclusive_and_one_won_above_is_not() {
        BigDecimal hundred = won(100);
        assertThat(DisciplineRules.dropped(hundred, won(92))).isTrue();
        assertThat(DisciplineRules.dropped(hundred, won(93))).isFalse();
        assertThat(DisciplineRules.rose(hundred, won(108))).isTrue();
        assertThat(DisciplineRules.rose(hundred, won(107))).isFalse();
        // a non-positive previous value can neither fall nor rise (0 <= 0 would otherwise count)
        assertThat(DisciplineRules.dropped(won(0), won(0))).isFalse();
        assertThat(DisciplineRules.rose(won(0), won(0))).isFalse();
    }

    @Test
    void team_boundary_exactly_minus_eight_counts_one_won_above_does_not() {
        // V2 one won above the line: turn 2 is no longer a drop turn, so change c1 and the bound sell 5 stop being panic
        DisciplineFacts g = golden();
        DisciplineFacts shifted = with(g,
                values(30_300_000, 27_876_001, 25_645_921, 23_594_247, 24_000_000, 24_100_000), g.changes(), g.orders());
        Result r = DisciplineCalculator.calculate(shifted);
        assertThat(r.panicFormationChanges()).isZero();
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("3600000");
        // V3 below the line instead of one won above: turn 3 becomes a drop turn, c2 (20 to 10) turns panic, sell 6 follows
        DisciplineFacts fallen = with(g,
                values(30_300_000, 27_876_000, 25_645_920, 23_594_247, 24_000_000, 24_100_000), g.changes(), g.orders());
        Result f = DisciplineCalculator.calculate(fallen);
        assertThat(f.panicFormationChanges()).isEqualTo(2);
        assertThat(f.panicSellNotional().toPlainString()).isEqualTo("4800000");
    }

    @Test
    void a_drop_turn_without_a_lower_forward_target_is_not_panic() {
        DisciplineFacts g = golden();
        List<ChangeFact> raised = new ArrayList<>(g.changes());
        raised.set(0, new ChangeFact(1, 2, 20, 30));
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), raised, g.orders()));
        assertThat(r.panicFormationChanges()).isZero();
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("3600000");
        // a change that leaves the forward share equal is not "lower" either
        List<ChangeFact> same = new ArrayList<>(g.changes());
        same.set(0, new ChangeFact(1, 2, 20, 20));
        assertThat(DisciplineCalculator.calculate(with(g, g.turnValues(), same, g.orders())).panicFormationChanges())
                .isZero();
    }

    @Test
    void several_changes_in_a_turn_are_judged_from_the_first_before_to_the_last_after() {
        DisciplineFacts g = golden();
        // turn 4 is a drop turn: 10 to 40 to 5 ends lower than it started (10), so both rows are panic and sell 10 follows
        List<ChangeFact> list = new ArrayList<>(g.changes());
        list.set(2, new ChangeFact(3, 4, 10, 40));
        list.set(3, new ChangeFact(4, 4, 40, 5));
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), list, g.orders()));
        assertThat(r.panicFormationChanges()).isEqualTo(3);
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("4250000");
        // the last row by id decides the end: with the ids swapped the same rows end at 40 and the turn is calm
        List<ChangeFact> swapped = new ArrayList<>(g.changes());
        swapped.set(2, new ChangeFact(4, 4, 10, 40));
        swapped.set(3, new ChangeFact(3, 4, 40, 5));
        Result s = DisciplineCalculator.calculate(with(g, g.turnValues(), swapped, g.orders()));
        assertThat(s.panicFormationChanges()).isEqualTo(1);
        // 30 to 50 to 40 ends above its start (30) although the last row alone is a fall: the start is the FIRST row's before
        List<ChangeFact> upThenDown = new ArrayList<>(g.changes());
        upThenDown.set(2, new ChangeFact(3, 4, 30, 50));
        upThenDown.set(3, new ChangeFact(4, 4, 50, 40));
        assertThat(DisciplineCalculator.calculate(with(g, g.turnValues(), upThenDown, g.orders())).panicFormationChanges())
                .isEqualTo(1);
    }

    @Test
    void asset_basis_not_team_basis_decides_a_sell() {
        // Sell 9 sits in a team drop turn but its asset did not fall; it stays out of the panic amount
        Result r = DisciplineCalculator.calculate(golden());
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("4100000");
        // the same sell on an asset that fell 8 % counts
        DisciplineFacts g = golden();
        List<OrderFact> orders = new ArrayList<>(g.orders());
        orders.set(8, order(9, 4, OrderSide.SELL, C, 250_000, null, 92, 100));
        assertThat(DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), orders)).panicSellNotional()
                .toPlainString()).isEqualTo("4350000");
    }

    @Test
    void turn_zero_orders_are_never_judged() {
        DisciplineFacts g = golden();
        List<OrderFact> onlyZero = List.of(
                order(1, 0, OrderSide.BUY, A, 50_000_000, null, 200, 100),
                new OrderFact(2, 0, OrderSide.SELL, B, won(1_000_000), null, null, null));
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), onlyZero));
        assertThat(r.tradeNotional().toPlainString()).isEqualTo("0");
        assertThat(r.panicSellShare()).isNull();
        assertThat(r.chaseBuyShare()).isNull();
        assertThat(r.undisciplinedShare()).isNull();
    }

    @Test
    void no_sells_leaves_the_panic_share_null_and_no_buys_the_chase_share() {
        DisciplineFacts g = golden();
        List<OrderFact> buysOnly = g.orders().stream().filter(o -> o.side() == OrderSide.BUY).toList();
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), buysOnly));
        assertThat(r.sellNotional().toPlainString()).isEqualTo("0");
        assertThat(r.panicSellShare()).isNull();
        assertThat(r.chaseBuyShare().toPlainString()).isEqualTo("0.43137255");
        assertThat(r.undisciplinedShare().toPlainString()).isEqualTo("0.43137255");
        List<OrderFact> sellsOnly = g.orders().stream().filter(o -> o.side() == OrderSide.SELL).toList();
        Result s = DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), sellsOnly));
        assertThat(s.chaseBuyShare()).isNull();
        assertThat(s.panicSellShare().toPlainString()).isEqualTo("0.61194030");
    }

    @Test
    void ratios_use_one_division_with_half_up_at_eight_digits() {
        DisciplineFacts g = golden();
        // 1 won panic out of 2 won sells: exactly 0.5
        List<OrderFact> tiny = List.of(order(1, 1, OrderSide.SELL, A, 1, null, 92, 100),
                order(2, 1, OrderSide.SELL, B, 1, null, 100, 100));
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), tiny));
        assertThat(r.panicSellShare().toPlainString()).isEqualTo("0.50000000");
        // 1 of 3: 0.333..., 2 of 3: 0.666... rounds up to 0.66666667
        List<OrderFact> thirds = List.of(order(1, 1, OrderSide.SELL, A, 2, null, 92, 100),
                order(2, 1, OrderSide.SELL, B, 1, null, 100, 100));
        Result t = DisciplineCalculator.calculate(with(g, g.turnValues(), g.changes(), thirds));
        assertThat(t.panicSellShare().toPlainString()).isEqualTo("0.66666667");
    }

    @Test
    void trade_notional_is_sells_plus_buys_of_turns_one_to_t_minus_one() {
        Result r = DisciplineCalculator.calculate(golden());
        assertThat(r.tradeNotional()).isEqualByComparingTo(r.sellNotional().add(r.buyNotional()));
        // 18,000,000 + ... of the golden orders 1..12 only; the turn-0 order of 50,000,000 is not in it
        BigDecimal inRange = golden().orders().stream().filter(o -> o.turnNo() >= 1).map(OrderFact::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r.tradeNotional()).isEqualByComparingTo(inRange);
    }

    @Test
    void the_first_turn_is_compared_with_the_initial_cash() {
        // V0 = 30,000,000 (initial cash), V1 = 27,600,000 is exactly -8 %: turn 1 is a drop turn, the change lowers forward
        DisciplineFacts first = new DisciplineFacts(3, won(30_000_000), values(27_600_000, 27_600_000, 27_600_000),
                List.of(new ChangeFact(1, 1, 30, 20)),
                List.of(order(1, 1, OrderSide.SELL, A, 1_000, 1L, 100, 100)));
        Result r = DisciplineCalculator.calculate(first);
        assertThat(r.panicFormationChanges()).isEqualTo(1);
        assertThat(r.panicSellNotional().toPlainString()).isEqualTo("1000");
        // one won above the line: no drop
        DisciplineFacts above = new DisciplineFacts(3, won(30_000_000), values(27_600_001, 27_600_001, 27_600_001),
                first.changes(), first.orders());
        assertThat(DisciplineCalculator.calculate(above).panicFormationChanges()).isZero();
    }

    @Test
    void change_range_is_turns_one_to_t_minus_one() {
        DisciplineFacts g = golden();
        List<ChangeFact> edge = List.of(new ChangeFact(1, 0, 30, 20), new ChangeFact(2, 1, 30, 20),
                new ChangeFact(3, 5, 20, 10), new ChangeFact(4, 6, 10, 5));
        Result r = DisciplineCalculator.calculate(with(g, g.turnValues(), edge, List.of()));
        // turn 0 and turn T (6) are out; turns 1 and T-1 (5) are in
        assertThat(r.formationChanges()).isEqualTo(2);
        // a two-row turn counts by row, and panic counts by row too
        List<ChangeFact> twoRows = List.of(new ChangeFact(1, 2, 30, 25), new ChangeFact(2, 2, 25, 20));
        Result t = DisciplineCalculator.calculate(with(g, g.turnValues(), twoRows, List.of()));
        assertThat(t.formationChanges()).isEqualTo(2);
        assertThat(t.panicFormationChanges()).isEqualTo(2);
    }

    @Test
    void single_turn_season_has_nothing_to_judge() {
        DisciplineFacts one = new DisciplineFacts(1, won(30_000_000), values(30_300_000), List.of(), List.of());
        Result r = DisciplineCalculator.calculate(one);
        assertThat(r.tradeNotional().toPlainString()).isEqualTo("0");
        assertThat(r.formationChanges()).isZero();
        assertThat(r.undisciplinedShare()).isNull();
    }

    @Test
    void inconsistent_sources_stop_with_a_fixed_sentence() {
        DisciplineFacts g = golden();
        // an order at turn T or later, or a negative turn
        List<OrderFact> late = new ArrayList<>(g.orders());
        late.add(order(14, 6, OrderSide.BUY, A, 1_000, null, 100, 100));
        rejected(with(g, g.turnValues(), g.changes(), late));
        List<OrderFact> negative = new ArrayList<>(g.orders());
        negative.add(order(14, -1, OrderSide.BUY, A, 1_000, null, 100, 100));
        rejected(with(g, g.turnValues(), g.changes(), negative));
        // a missing turn price (left join) must refuse the season, not drop the order
        List<OrderFact> noPrice = new ArrayList<>(g.orders());
        noPrice.set(0, new OrderFact(1, 1, OrderSide.BUY, A, won(1_000_000), null, null, won(100)));
        rejected(with(g, g.turnValues(), g.changes(), noPrice));
        List<OrderFact> noPrevious = new ArrayList<>(g.orders());
        noPrevious.set(0, new OrderFact(1, 1, OrderSide.BUY, A, won(1_000_000), null, won(108), null));
        rejected(with(g, g.turnValues(), g.changes(), noPrevious));
        List<OrderFact> zeroPrice = new ArrayList<>(g.orders());
        zeroPrice.set(0, order(1, 1, OrderSide.BUY, A, 1_000_000, null, 0, 100));
        rejected(with(g, g.turnValues(), g.changes(), zeroPrice));
        // notional not a positive whole won
        List<OrderFact> zeroNotional = new ArrayList<>(g.orders());
        zeroNotional.set(0, order(1, 1, OrderSide.BUY, A, 0, null, 108, 100));
        rejected(with(g, g.turnValues(), g.changes(), zeroNotional));
        List<OrderFact> fractional = new ArrayList<>(g.orders());
        fractional.set(0, new OrderFact(1, 1, OrderSide.BUY, A, new BigDecimal("1000000.5000"), null, won(108), won(100)));
        rejected(with(g, g.turnValues(), g.changes(), fractional));
        // bound to a change that does not exist, or to one of another turn
        List<OrderFact> unknownChange = new ArrayList<>(g.orders());
        unknownChange.set(2, order(3, 2, OrderSide.SELL, A, 3_000_000, 99L, 92, 100));
        rejected(with(g, g.turnValues(), g.changes(), unknownChange));
        List<OrderFact> otherTurnChange = new ArrayList<>(g.orders());
        otherTurnChange.set(2, order(3, 2, OrderSide.SELL, A, 3_000_000, 2L, 92, 100));
        rejected(with(g, g.turnValues(), g.changes(), otherTurnChange));
        // team values that do not run 1..T
        rejected(with(g, g.turnValues().subList(0, 5), g.changes(), g.orders()));
        List<TurnValue> gap = new ArrayList<>(g.turnValues());
        gap.set(2, new TurnValue(4, won(25_645_921)));
        rejected(with(g, gap, g.changes(), g.orders()));
        rejected(new DisciplineFacts(0, won(30_000_000), List.of(), List.of(), List.of()));
        rejected(new DisciplineFacts(6, won(0), g.turnValues(), g.changes(), g.orders()));
    }
    // ------------------------------------------------------------------ generated seasons

    private static DisciplineFacts generated(long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        int turns = 2 + rnd.nextInt(12);
        List<TurnValue> values = new ArrayList<>();
        long value = 30_000_000;
        for (int t = 1; t <= turns; t++) {
            value = Math.max(1_000, Math.round(value * (0.85 + rnd.nextDouble() * 0.3)));
            values.add(new TurnValue(t, won(value)));
        }
        List<ChangeFact> changes = new ArrayList<>();
        int forward = 30;
        long nextId = 1;
        for (int t = 1; t < turns; t++) {
            int count = rnd.nextDouble() < 0.3 ? 1 + rnd.nextInt(2) : 0;
            for (int i = 0; i < count; i++) {
                int after = forward;
                while (after == forward) {
                    after = 5 + rnd.nextInt(56);
                }
                changes.add(new ChangeFact(nextId++, t, forward, after));
                forward = after;
            }
        }
        AssetKey[] assets = {A, B, C};
        List<OrderFact> orders = new ArrayList<>();
        long orderId = 1;
        for (int t = 1; t < turns; t++) {
            final int turn = t;
            List<ChangeFact> here = changes.stream().filter(c -> c.turnNo() == turn).toList();
            for (int i = rnd.nextInt(4); i > 0; i--) {
                long previous = 10_000;
                long price = Math.round(previous * (0.88 + rnd.nextDouble() * 0.24));
                Long bound = !here.isEmpty() && rnd.nextDouble() < 0.3 ? here.get(rnd.nextInt(here.size())).id() : null;
                orders.add(order(orderId++, t, rnd.nextBoolean() ? OrderSide.BUY : OrderSide.SELL,
                        assets[rnd.nextInt(3)], 1_000L * (1 + rnd.nextInt(5_000)), bound, price, previous));
            }
        }
        return new DisciplineFacts(turns, won(30_000_000), values, changes, orders);
    }

    @Test
    void generated_seasons_keep_the_invariants_and_ignore_input_order() {
        for (long seed = 0; seed < 200; seed++) {
            DisciplineFacts facts = generated(seed);
            Result r = DisciplineCalculator.calculate(facts);
            assertThat(r.panicSellNotional()).as("seed " + seed).isLessThanOrEqualTo(r.sellNotional());
            assertThat(r.chaseBuyNotional()).as("seed " + seed).isLessThanOrEqualTo(r.buyNotional());
            assertThat(r.tradeNotional()).as("seed " + seed).isEqualByComparingTo(r.sellNotional().add(r.buyNotional()));
            assertThat(r.panicFormationChanges()).as("seed " + seed).isLessThanOrEqualTo(r.formationChanges());
            for (BigDecimal share : new BigDecimal[] {r.panicSellShare(), r.chaseBuyShare(), r.undisciplinedShare()}) {
                if (share != null) {
                    assertThat(share).as("seed " + seed).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                    assertThat(share.scale()).isEqualTo(8);
                }
            }
            assertThat(r.panicSellShare() == null).as("seed " + seed).isEqualTo(r.sellNotional().signum() == 0);
            List<OrderFact> shuffledOrders = new ArrayList<>(facts.orders());
            java.util.Collections.shuffle(shuffledOrders, new java.util.Random(seed));
            List<ChangeFact> shuffledChanges = new ArrayList<>(facts.changes());
            java.util.Collections.shuffle(shuffledChanges, new java.util.Random(seed));
            assertThat(DisciplineCalculator.calculate(with(facts, facts.turnValues(), shuffledChanges, shuffledOrders)))
                    .as("seed " + seed).isEqualTo(r);
        }
    }

    @Test
    void appending_a_later_turn_leaves_the_earlier_verdicts_unchanged() {
        // Prefix property: nothing at or after the new last turn can reach back into the judged range
        for (long seed = 0; seed < 200; seed++) {
            DisciplineFacts facts = generated(seed);
            List<TurnValue> longer = new ArrayList<>(facts.turnValues());
            long last = facts.turnValues().get(facts.turnValues().size() - 1).totalValue().longValueExact();
            longer.add(new TurnValue(facts.reportTurn() + 1, won(Math.max(1_000, last / 2))));
            DisciplineFacts extended = new DisciplineFacts(facts.reportTurn() + 1, facts.initialCash(), longer,
                    facts.changes(), facts.orders());
            assertThat(DisciplineCalculator.calculate(extended)).as("seed " + seed)
                    .isEqualTo(DisciplineCalculator.calculate(facts));
        }
    }
}
