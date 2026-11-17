package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The extracted benchmark series must keep the value the season report already shows. */
class Phase0MarketBaselineTest {

    private final MarketBaselineSeries series = new Phase0MarketBaseline();

    @Test
    void the_series_has_one_value_per_turn_from_zero_to_the_report_turn() {
        SeasonReportSources src = SeasonReportFixtures.threeTurns();

        List<BigDecimal> values = series.values(src);

        // 499 shares at 30,000 + 213 shares at 70,000 after fees leave 15,570 cash; value = shares x price + 15,570
        assertThat(values).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("29895570"), new BigDecimal("30820570"), new BigDecimal("28970570"),
                new BigDecimal("32884069"));
    }

    @Test
    void the_last_value_equals_the_baseline_the_report_shows() {
        SeasonReportSources src = SeasonReportFixtures.threeTurns();

        List<BigDecimal> values = series.values(src);

        assertThat(values.get(values.size() - 1))
                .isEqualByComparingTo(SeasonReportCalculator.calculate(src).baselineFinalValue());
    }

    @Test
    void the_first_return_is_measured_against_the_initial_cash_not_the_turn_zero_value() {
        SeasonReportSources src = SeasonReportFixtures.threeTurns();

        List<BigDecimal> returns = series.returns(src);

        assertThat(returns).hasSize(3);
        // (30,820,570 - 30,000,000) / 30,000,000 = 0.0273523333...; against values[0] it would be 0.03094...
        assertThat(returns.get(0).setScale(8, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("0.02735233");
        // (28,970,570 - 30,820,570) / 30,820,570 = -0.0600...
        assertThat(returns.get(1).setScale(4, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("-0.0600");
    }

    @Test
    void the_value_at_turn_zero_is_the_initial_cash_less_the_purchase_fees() {
        SeasonReportSources src = SeasonReportFixtures.threeTurns();

        BigDecimal first = series.values(src).get(0);

        assertThat(first).isLessThan(src.season().initialCash());
    }
}
