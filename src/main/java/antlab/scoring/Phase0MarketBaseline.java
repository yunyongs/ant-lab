package antlab.scoring;

import antlab.common.money.MoneyMath;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The Phase 0 benchmark: two proxy assets bought at the turn-0 price and held to the end (SQL {@code sr_baseline}).
 * Budget = initial cash x weight, whole shares = {@code teamQty}, remaining cash = initial cash - sum(quantity x
 * turn-0 price + fee), value at turn t = sum(quantity x turn-t price) + remaining cash. The season report and the
 * season score both read it from here, so they cannot drift apart.
 */
@Component
public final class Phase0MarketBaseline implements MarketBaselineSeries {

    private static final AssetKey BENCH_KR = new AssetKey(ReportRules.BENCH_KR_MARKET, ReportRules.BENCH_KR_TICKER);
    private static final AssetKey BENCH_US = new AssetKey(ReportRules.BENCH_US_MARKET, ReportRules.BENCH_US_TICKER);

    /** One leg of the benchmark: the proxy asset and its share of the initial cash. */
    private record Leg(AssetKey key, BigDecimal weight) {
    }

    private static final List<Leg> LEGS = List.of(
            new Leg(BENCH_KR, ReportRules.BENCH_KR_WEIGHT), new Leg(BENCH_US, ReportRules.BENCH_US_WEIGHT));

    @Override
    public List<BigDecimal> values(SeasonReportSources src) {
        int turns = src.season().currentTurnNo();
        List<BigDecimal> out = new ArrayList<>(turns + 1);
        for (int turnNo = 0; turnNo <= turns; turnNo++) {
            out.add(valueAt(src, turnNo));
        }
        return out;
    }

    /** The benchmark value at one turn, in won. */
    static BigDecimal valueAt(SeasonReportSources src, int turnNo) {
        SeasonFacts season = src.season();
        BigDecimal cash = season.initialCash();
        BigDecimal held = BigDecimal.ZERO;
        for (Leg leg : LEGS) {
            AssetKey key = leg.key();
            List<BigDecimal> prices = src.benchmarkPrices().get(key);
            BigDecimal rate = MoneyMath.feeRate(key.market(), season.feeRateKr(), season.feeRateUs(), season.fxSpreadRate());
            BigDecimal firstPrice = prices.get(0);
            int quantity = MoneyMath.teamQty(season.initialCash().multiply(leg.weight()), firstPrice, rate);
            BigDecimal fee = quantity == 0 ? BigDecimal.ZERO : MoneyMath.fee(firstPrice, quantity, rate);
            BigDecimal count = BigDecimal.valueOf(quantity);
            cash = cash.subtract(count.multiply(firstPrice).add(fee));
            held = held.add(count.multiply(prices.get(turnNo)));
        }
        return held.add(cash);
    }
}
