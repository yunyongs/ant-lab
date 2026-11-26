package antlab.scoring;

import antlab.common.money.MoneyMath;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Computes the in-season market benchmark line from the same stored prices as the season report. */
@Component
public final class MarketBenchmarkLine {

    private static final Logger LOG = LoggerFactory.getLogger(MarketBenchmarkLine.class);

    private final SeasonReportQuery reportQuery;

    public MarketBenchmarkLine(SeasonReportQuery reportQuery) {
        this.reportQuery = reportQuery;
    }

    /** Returns whole-won values for turns 0..current, or empty when the benchmark cannot be computed. */
    public Optional<List<BigDecimal>> wonValues(long seasonId, int currentTurnNo, BigDecimal initialCash,
            BigDecimal feeRateKr, BigDecimal feeRateUs, BigDecimal fxSpreadRate) {
        if (currentTurnNo == 0) {
            try {
                return Optional.of(List.of(MoneyMath.exactWon(initialCash)));
            } catch (IllegalArgumentException failure) {
                logUnavailable(seasonId, "whole-won check");
                return Optional.empty();
            }
        }

        Map<AssetKey, List<BigDecimal>> prices = reportQuery.benchmarkPrices(seasonId, currentTurnNo);
        try {
            Phase0MarketBaseline.requirePrices(prices, currentTurnNo);
        } catch (IllegalStateException failure) {
            logUnavailable(seasonId, "price check");
            return Optional.empty();
        }

        try {
            List<BigDecimal> rawValues = new ArrayList<>(currentTurnNo + 1);
            rawValues.add(initialCash);
            for (int turnNo = 1; turnNo <= currentTurnNo; turnNo++) {
                rawValues.add(Phase0MarketBaseline.valueAt(initialCash, feeRateKr, feeRateUs,
                        fxSpreadRate, prices, turnNo));
            }
            return Optional.of(rawValues.stream().map(MoneyMath::exactWon).toList());
        } catch (IllegalArgumentException failure) {
            logUnavailable(seasonId, "valuation");
            return Optional.empty();
        }
    }

    private static void logUnavailable(long seasonId, String check) {
        LOG.error("시장 기준선을 계산할 수 없다: seasonId={} check={}", seasonId, check);
    }
}
