package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderSide;
import antlab.scoring.BaselineResult.Fill;
import antlab.scoring.BaselineResult.TurnState;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Four baselines on a 3-asset, 6-turn fixture, compared with the hand-checked vectors (initial cash 1,000,000; KR fee 0.1 %, US fee
 * 0.6 %). One band-triggered rebalance in each of 60/40 (turn 3) and the two formation baselines (turn 1), and one forced rebalance
 * of the following baseline at its formation change (turn 3, a large switch) and another at turn 5, where a two-point switch leaves the
 * band unbroken, so only the forced rule makes it trade (one share sold).
 *
 * <p>Hand trace of the opening purchase of MARKET: A budget 500,000 / (10,000 x 1.001) = 49.95 gives 49 shares, fee 490; B budget
 * 500,000 / (20,000 x 1.006) = 24.85 gives 24 shares, fee 2,880; cash = 1,000,000 - 490,000 - 490 - 480,000 - 2,880 = 26,630; value at
 * turn 0 = 26,630 + 490,000 + 480,000 = 996,630.
 * 60/40 at turn 3 (prices 12,000 / 17,000 / 50,300, holdings 29 / 14 / 7, cash 77,680): value 1,015,780; the bond slot is 352,100 =
 * 34.66 %, 5.34 points under its 40 %, so the band (5) is broken, while at turn 2 it was 35.14 % (4.86 points) and was not. The
 * rebalance sells 4 of the first asset, then buys the bond (key order KR:CCC before US:BBB) and 3 of the third asset out of the
 * proceeds plus the 77,680 of cash.
 */
class BaselineSimulatorGoldenTest {

    private static final BaselineVectors VECTORS = BaselineVectors.load();

    @ParameterizedTest
    @EnumSource(BaselineKey.class)
    void turn_values_cash_and_quantities_match_the_vectors(BaselineKey key) {
        BaselineResult result = VECTORS.simulate(key);
        JsonNode expected = VECTORS.baseline(key).get("expected");

        assertThat(result.states()).hasSize(7);
        for (int turn = 0; turn <= 6; turn++) {
            TurnState state = result.states().get(turn);
            assertThat(state.value().toPlainString()).as("value at turn " + turn)
                    .isEqualTo(expected.get("turnValues").get(turn).asString());
            assertThat(state.cash().toPlainString()).as("cash at turn " + turn)
                    .isEqualTo(expected.get("turnCash").get(turn).asString());
            // the vectors list all three assets; a baseline reports only its own legs
            Map<AssetKey, Integer> wanted = quantities(expected.get("turnQuantities").get(turn));
            wanted.keySet().retainAll(state.quantities().keySet());
            assertThat(state.quantities()).as("quantities at turn " + turn).isEqualTo(wanted);
        }
    }

    @ParameterizedTest
    @EnumSource(BaselineKey.class)
    void fills_fees_and_counts_match_the_vectors(BaselineKey key) {
        BaselineResult result = VECTORS.simulate(key);
        JsonNode expected = VECTORS.baseline(key).get("expected");

        List<String> actual = new ArrayList<>();
        for (Fill fill : result.fills()) {
            actual.add(describe(fill.turnNo(), fill.side().name(), fill.asset(), fill.quantity(), fill.fee(), fill.forced()));
        }
        List<String> wanted = new ArrayList<>();
        for (JsonNode fill : expected.get("fills")) {
            wanted.add(describe(fill.get("turnNo").asInt(), fill.get("side").asString(),
                    BaselineVectors.key(fill.get("asset").asString()), fill.get("quantity").asInt(),
                    new BigDecimal(fill.get("fee").asString()), fill.get("forced").asBoolean()));
        }
        assertThat(actual).isEqualTo(wanted);
        assertThat(result.totalFee().toPlainString()).isEqualTo(expected.get("totalFee").asString());
        assertThat(result.rebalanceCount()).isEqualTo(expected.get("rebalanceCount").asInt());
        assertThat(result.forcedChangeCount()).isEqualTo(expected.get("forcedChangeCount").asInt());
        assertThat(result.forcedChangeFee().toPlainString()).isEqualTo(expected.get("forcedChangeFee").asString());
    }

    @Test
    void every_kind_of_rebalance_is_present_in_the_fixture() {
        assertThat(VECTORS.simulate(BaselineKey.MARKET).rebalanceCount()).isZero();
        assertThat(VECTORS.simulate(BaselineKey.SIXTY_FORTY).rebalanceCount()).isEqualTo(1);
        assertThat(VECTORS.simulate(BaselineKey.FORMATION_FIXED).rebalanceCount()).isEqualTo(1);
        BaselineResult follow = VECTORS.simulate(BaselineKey.FORMATION_FOLLOW);
        assertThat(follow.rebalanceCount()).isEqualTo(3);
        assertThat(follow.forcedChangeCount()).isEqualTo(2);
        assertThat(follow.forcedChangeFee()).isPositive();
        assertThat(follow.fills()).filteredOn(Fill::forced).extracting(Fill::turnNo).containsOnly(3, 5);
    }

    @Test
    void sells_come_before_buys_inside_a_rebalance() {
        List<Fill> fills = VECTORS.simulate(BaselineKey.SIXTY_FORTY).fills().stream().filter(f -> f.turnNo() == 3).toList();
        assertThat(fills).extracting(Fill::side).containsExactly(OrderSide.SELL, OrderSide.BUY, OrderSide.BUY);
        // buys follow (market, ticker) order: KR:CCC before US:BBB
        assertThat(fills).extracting(f -> f.asset().ticker()).containsExactly("AAA", "CCC", "BBB");
    }

    @Test
    void the_last_turn_has_no_trade() {
        for (BaselineKey key : BaselineKey.values()) {
            assertThat(VECTORS.simulate(key).fills()).noneMatch(f -> f.turnNo() >= 6);
        }
    }

    private static String describe(int turn, String side, AssetKey asset, int quantity, BigDecimal fee, boolean forced) {
        return turn + " " + side + " " + asset.market() + ":" + asset.ticker() + " " + quantity + " " + fee.toPlainString() + " "
                + forced;
    }

    private static Map<AssetKey, Integer> quantities(JsonNode node) {
        Map<AssetKey, Integer> out = new TreeMap<>(BaselineSimulator.ASSET_ORDER);
        for (var entry : node.properties()) {
            out.put(BaselineVectors.key(entry.getKey()), entry.getValue().asInt());
        }
        return out;
    }
}
