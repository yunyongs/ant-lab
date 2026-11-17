package antlab.scoring;

import antlab.account.OrderSide;
import antlab.common.money.MoneyMath;
import antlab.scoring.BaselineResult.Fill;
import antlab.scoring.BaselineResult.TurnState;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Plays one baseline portfolio over a season's own price series, with integer shares and fees. Pure and deterministic: the result at turn t
 * reads only prices and targets of turns up to t. Money is whole won; fees come from {@link MoneyMath#fee}, the opening quantity from
 * {@link MoneyMath#teamQty}, and nothing here rounds money.
 *
 * <p>Convention (erd D23): the value at turn t is cash plus quantities times that turn's price, before any trade of turn t. The opening
 * purchase happens at turn 0. A rebalance at turn t (1 to T-1, none at T) trades at that turn's price and holds from turn t+1.
 */
public final class BaselineSimulator {

    /** (market, ticker) byte order, used for buys and for every map of assets. */
    static final Comparator<AssetKey> ASSET_ORDER =
            Comparator.comparing(AssetKey::market).thenComparing(AssetKey::ticker);

    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000);

    /** Fee rate inputs of the season; one rate per market via {@link MoneyMath#feeRate}. */
    public record FeeRates(BigDecimal kr, BigDecimal us, BigDecimal fxSpread) {
        BigDecimal rate(String market) {
            return MoneyMath.feeRate(market, kr, us, fxSpread);
        }
    }

    private BaselineSimulator() {
    }

    /**
     * @param prices one list per leg asset, turn 0..T, whole won (integer values at any scale)
     * @throws IllegalStateException with a fixed sentence (no amounts) when the inputs contradict each other
     */
    public static BaselineResult simulate(BaselineSpec spec, TargetSchedule schedule,
            Map<AssetKey, List<BigDecimal>> prices, BigDecimal initialCash, FeeRates fees) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(fees, "fees");
        BigDecimal cash0 = wholeWon(initialCash, "the initial cash must be a positive whole amount");
        if (schedule.start().size() != spec.slots().size()) {
            throw invalid("the target needs one percentage per slot");
        }
        Map<AssetKey, long[]> series = normalise(spec, prices);
        int lastTurn = series.values().iterator().next().length - 1;
        if (!schedule.switches().isEmpty()
                && schedule.switches().get(schedule.switches().size() - 1).turnNo() >= lastTurn) {
            throw invalid("a switch must come before the last turn");
        }

        Run run = new Run(spec, schedule, series, cash0, fees, lastTurn);
        return run.play();
    }

    private static Map<AssetKey, long[]> normalise(BaselineSpec spec, Map<AssetKey, List<BigDecimal>> prices) {
        Map<AssetKey, long[]> out = new TreeMap<>(ASSET_ORDER);
        int length = -1;
        for (Slot slot : spec.slots()) {
            for (Leg leg : slot.legs()) {
                List<BigDecimal> list = prices.get(leg.asset());
                if (list == null) {
                    throw invalid("a leg asset has no price series");
                }
                if (length >= 0 && list.size() != length) {
                    throw invalid("every price series must cover the same turns");
                }
                length = list.size();
                long[] values = new long[list.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = wholeWon(list.get(i), "a price must be a positive whole amount").longValueExact();
                }
                out.put(leg.asset(), values);
            }
        }
        if (out.isEmpty()) {
            throw invalid("a baseline needs at least one leg");
        }
        if (length < 2) {
            throw invalid("a season needs at least one turn after turn zero");
        }
        return out;
    }

    private static BigDecimal wholeWon(BigDecimal value, String reason) {
        if (value == null || value.signum() <= 0) {
            throw invalid(reason);
        }
        try {
            return new BigDecimal(value.toBigIntegerExact());
        } catch (ArithmeticException ex) {
            throw invalid(reason);
        }
    }

    static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Baseline input is inconsistent: " + reason);
    }

    /** Mutable state of one simulation. */
    private static final class Run {
        private final BaselineSpec spec;
        private final TargetSchedule schedule;
        private final Map<AssetKey, long[]> prices;
        private final BigDecimal initialCash;
        private final FeeRates fees;
        private final int lastTurn;
        private final Map<AssetKey, Integer> quantities = new TreeMap<>(ASSET_ORDER);
        private final List<TurnState> states = new ArrayList<>();
        private final List<Fill> fills = new ArrayList<>();
        private BigDecimal cash;
        private BigDecimal totalFee = BigDecimal.ZERO;
        private BigDecimal forcedFee = BigDecimal.ZERO;
        private int rebalances;
        private int forcedRebalances;

        Run(BaselineSpec spec, TargetSchedule schedule, Map<AssetKey, long[]> prices, BigDecimal initialCash,
                FeeRates fees, int lastTurn) {
            this.spec = spec;
            this.schedule = schedule;
            this.prices = prices;
            this.initialCash = initialCash;
            this.fees = fees;
            this.lastTurn = lastTurn;
            this.cash = initialCash;
            for (AssetKey asset : prices.keySet()) {
                quantities.put(asset, 0);
            }
        }

        BaselineResult play() {
            openingPurchase();
            for (int turn = 0; turn <= lastTurn; turn++) {
                BigInteger total = record(turn);
                if (turn >= 1 && turn < lastTurn) {
                    boolean forced = schedule.switchesAt(turn);
                    if (forced || bandBroken(turn, total)) {
                        rebalance(turn, total, forced);
                    }
                }
            }
            return new BaselineResult(spec.key(), states, fills, rebalances, forcedRebalances, forcedFee, totalFee);
        }

        private void openingPurchase() {
            List<Integer> target = schedule.start();
            for (int i = 0; i < spec.slots().size(); i++) {
                for (Leg leg : spec.slots().get(i).legs()) {
                    BigDecimal budget = new BigDecimal(initialCash.toBigIntegerExact()
                            .multiply(BigInteger.valueOf((long) target.get(i) * leg.sharePercent()))
                            .divide(TEN_THOUSAND));
                    buy(0, leg.asset(), budget, false);
                }
            }
        }

        private BigInteger record(int turn) {
            BigDecimal value = cash;
            for (Map.Entry<AssetKey, Integer> held : quantities.entrySet()) {
                value = value.add(BigDecimal.valueOf(price(held.getKey(), turn)).multiply(BigDecimal.valueOf(held.getValue())));
            }
            states.add(new TurnState(turn, cash, quantities, value));
            return value.toBigIntegerExact();
        }

        /** True when some slot is more than the band away from its target: {@code |100*v - f*V| > band*V}, integers only. */
        private boolean bandBroken(int turn, BigInteger total) {
            if (spec.bandPercent() == null) {
                return false;
            }
            List<Integer> target = schedule.targetAt(turn);
            BigInteger limit = BigInteger.valueOf(spec.bandPercent()).multiply(total);
            for (int i = 0; i < spec.slots().size(); i++) {
                BigInteger deviation = HUNDRED.multiply(slotValue(spec.slots().get(i), turn))
                        .subtract(BigInteger.valueOf(target.get(i)).multiply(total)).abs();
                if (deviation.compareTo(limit) > 0) {
                    return true;
                }
            }
            return false;
        }

        private BigInteger slotValue(Slot slot, int turn) {
            if (slot.isCash()) {
                return cash.toBigIntegerExact();
            }
            BigInteger sum = BigInteger.ZERO;
            for (Leg leg : slot.legs()) {
                sum = sum.add(BigInteger.valueOf(price(leg.asset(), turn))
                        .multiply(BigInteger.valueOf(quantities.get(leg.asset()))));
            }
            return sum;
        }

        /** Sells down to the target quantity first, then buys in asset order with the fee inside the budget. */
        private void rebalance(int turn, BigInteger total, boolean forced) {
            List<Integer> target = schedule.targetAt(turn);
            Map<AssetKey, BigInteger> targetValue = new TreeMap<>(ASSET_ORDER); // scaled by 10,000
            for (int i = 0; i < spec.slots().size(); i++) {
                for (Leg leg : spec.slots().get(i).legs()) {
                    targetValue.put(leg.asset(), total.multiply(BigInteger.valueOf((long) target.get(i) * leg.sharePercent())));
                }
            }
            BigDecimal feeBefore = totalFee;
            for (Map.Entry<AssetKey, BigInteger> entry : targetValue.entrySet()) {
                AssetKey asset = entry.getKey();
                BigInteger price = BigInteger.valueOf(price(asset, turn));
                BigInteger wanted = entry.getValue().divide(TEN_THOUSAND.multiply(price));
                int held = quantities.get(asset);
                if (BigInteger.valueOf(held).compareTo(wanted) > 0) {
                    sell(turn, asset, held - wanted.intValueExact(), forced);
                }
            }
            for (Map.Entry<AssetKey, BigInteger> entry : targetValue.entrySet()) {
                AssetKey asset = entry.getKey();
                BigInteger heldValue = BigInteger.valueOf(price(asset, turn)).multiply(BigInteger.valueOf(quantities.get(asset)));
                BigInteger gap = entry.getValue().subtract(heldValue.multiply(TEN_THOUSAND));
                if (gap.signum() <= 0) {
                    continue;
                }
                BigDecimal budget = new BigDecimal(gap.divide(TEN_THOUSAND).min(cash.toBigIntegerExact()));
                buy(turn, asset, budget, forced);
            }
            rebalances++;
            if (forced) {
                forcedRebalances++;
                forcedFee = forcedFee.add(totalFee.subtract(feeBefore));
            }
        }

        private void buy(int turn, AssetKey asset, BigDecimal budget, boolean forced) {
            BigDecimal price = BigDecimal.valueOf(price(asset, turn));
            BigDecimal rate = fees.rate(asset.market());
            int quantity = MoneyMath.teamQty(budget, price, rate);
            if (quantity == 0) {
                return;
            }
            BigDecimal fee = MoneyMath.fee(price, quantity, rate);
            cash = cash.subtract(price.multiply(BigDecimal.valueOf(quantity))).subtract(fee);
            if (cash.signum() < 0) {
                throw invalid("cash would go below zero");
            }
            quantities.merge(asset, quantity, Integer::sum);
            totalFee = totalFee.add(fee);
            fills.add(new Fill(turn, asset, OrderSide.BUY, quantity, price, fee, forced));
        }

        private void sell(int turn, AssetKey asset, int quantity, boolean forced) {
            BigDecimal price = BigDecimal.valueOf(price(asset, turn));
            BigDecimal fee = MoneyMath.fee(price, quantity, fees.rate(asset.market()));
            cash = cash.add(price.multiply(BigDecimal.valueOf(quantity))).subtract(fee);
            if (cash.signum() < 0) {
                throw invalid("cash would go below zero");
            }
            quantities.merge(asset, -quantity, Integer::sum);
            totalFee = totalFee.add(fee);
            fills.add(new Fill(turn, asset, OrderSide.SELL, quantity, price, fee, forced));
        }

        private long price(AssetKey asset, int turn) {
            return prices.get(asset)[turn];
        }
    }
}
