package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The turn summary line uses the report benchmark inputs and never reads beyond the current turn. */
class MarketBenchmarkLineTest {

    private static final BigDecimal INITIAL = BigDecimal.valueOf(1_000_000).setScale(4);
    private static final Map<AssetKey, List<BigDecimal>> PRICES = Map.of(
            Phase0MarketBaseline.BENCH_KR, prices(10_000, 11_000, 9_900),
            Phase0MarketBaseline.BENCH_US, prices(100_000, 105_000, 102_000));

    private static BigDecimal won(long value) {
        return BigDecimal.valueOf(value).setScale(4);
    }

    private static List<BigDecimal> prices(long... values) {
        return java.util.Arrays.stream(values).mapToObj(MarketBenchmarkLineTest::won).toList();
    }

    @Test
    void values_start_at_initial_cash_and_reuse_the_buy_and_hold_valuation() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);
        when(query.benchmarkPrices(7L, 2)).thenReturn(PRICES);

        List<BigDecimal> values = new MarketBenchmarkLine(query).wonValues(7L, 2, INITIAL,
                new BigDecimal("0.00015000"), new BigDecimal("0.00250000"), new BigDecimal("0.00400000")).orElseThrow();

        assertThat(values).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(won(1_000_000), won(1_066_326), won(1_000_426));
        verify(query).benchmarkPrices(7L, 2);
    }

    @Test
    void turn_zero_does_not_read_prices() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);

        assertThat(new MarketBenchmarkLine(query).wonValues(7L, 0, INITIAL, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO)).contains(List.of(BigDecimal.valueOf(1_000_000)));
        verifyNoInteractions(query);
    }

    @Test
    void missing_proxy_prices_make_the_whole_line_unavailable() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);
        when(query.benchmarkPrices(7L, 2)).thenReturn(Map.of(Phase0MarketBaseline.BENCH_KR, prices(10_000, 11_000, 9_900)));

        assertThat(new MarketBenchmarkLine(query).wonValues(7L, 2, INITIAL, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void a_fractional_later_price_makes_the_whole_line_unavailable() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);
        when(query.benchmarkPrices(7L, 1)).thenReturn(Map.of(
                Phase0MarketBaseline.BENCH_KR, prices(10_000, 10_000),
                Phase0MarketBaseline.BENCH_US, List.of(won(100_000), new BigDecimal("100000.5"))));

        assertThat(new MarketBenchmarkLine(query).wonValues(7L, 1, INITIAL, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void an_odd_initial_cash_makes_the_whole_line_unavailable_instead_of_failing() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);
        when(query.benchmarkPrices(7L, 2)).thenReturn(PRICES);

        assertThat(new MarketBenchmarkLine(query).wonValues(7L, 2, won(1_000_001), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void query_failures_are_not_treated_as_an_unavailable_benchmark() {
        SeasonReportQuery query = mock(SeasonReportQuery.class);
        RuntimeException failure = new RuntimeException("database failure");
        when(query.benchmarkPrices(7L, 1)).thenThrow(failure);

        assertThatThrownBy(() -> new MarketBenchmarkLine(query).wonValues(7L, 1, INITIAL, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO)).isSameAs(failure);
    }
}
