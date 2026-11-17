package antlab.scoring;

import static antlab.scoring.SeasonReportFixtures.KR;
import static antlab.scoring.SeasonReportFixtures.US;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MARKET is the report's own baseline: for every turn it equals the value the report calculator gives for a season reported at that turn. */
class BaselineMarketRegressionTest {

    private static SeasonReportSources reportedAt(SeasonReportSources full, int turn) {
        SeasonFacts facts = full.season();
        SeasonStatus status = turn == facts.pathLength() ? SeasonStatus.FINISHED : SeasonStatus.IN_PROGRESS;
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        full.poolReturns().forEach((asset, returns) -> pool.put(asset, returns.subList(0, turn)));
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        full.benchmarkPrices().forEach((asset, prices) -> bench.put(asset, prices.subList(0, turn + 1)));
        return new SeasonReportSources(
                SeasonReportFixtures.seasonWith(facts, status, facts.pathLength(), turn, facts.riskLimit().toPlainString()),
                full.turns().subList(0, turn), full.changes(), full.orders(),
                full.holdings().stream().filter(h -> h.turnNo() <= turn).toList(), full.lineup(), pool, bench,
                full.changeCosts(), full.recon());
    }

    @Test
    void market_values_equal_the_report_baseline_on_every_turn_of_the_fixture() {
        SeasonReportSources full = SeasonReportFixtures.threeTurns();
        SeasonFacts season = full.season();
        BaselineSpec spec = RepresentativeTable.REP_V1.resolve(Set.of(KR, US)).entry(BaselineKey.MARKET).spec();
        BaselineResult result = BaselineSimulator.simulate(spec, marketTarget(), full.benchmarkPrices(),
                season.initialCash(), new FeeRates(season.feeRateKr(), season.feeRateUs(), season.fxSpreadRate()));

        assertThat(result.turnValues()).hasSize(4);
        for (int turn = 1; turn <= 3; turn++) {
            BigDecimal reported = SeasonReportCalculator.calculate(reportedAt(full, turn)).baselineFinalValue();
            assertThat(result.turnValues().get(turn)).as("turn " + turn).isEqualByComparingTo(reported);
        }
        assertThat(result.finalValue()).isEqualByComparingTo(SeasonReportCalculator.calculate(full).baselineFinalValue());
    }

    @Test
    void the_market_baseline_never_trades_after_the_opening() {
        SeasonReportSources full = SeasonReportFixtures.threeTurns();
        SeasonFacts season = full.season();
        BaselineSpec spec = RepresentativeTable.REP_V1.resolve(Set.of(KR, US)).entry(BaselineKey.MARKET).spec();
        BaselineResult result = BaselineSimulator.simulate(spec, marketTarget(), full.benchmarkPrices(),
                season.initialCash(), new FeeRates(season.feeRateKr(), season.feeRateUs(), season.fxSpreadRate()));
        assertThat(result.fills()).allMatch(f -> f.turnNo() == 0);
        assertThat(result.rebalanceCount()).isZero();
    }

    private static TargetSchedule marketTarget() {
        return new TargetSchedule(BaselineRules.MARKET_TARGET, List.of());
    }
}
