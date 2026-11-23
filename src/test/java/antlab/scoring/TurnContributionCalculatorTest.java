package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.TurnContributionCalculator.Line;
import antlab.scoring.TurnContributionCalculator.SeasonTerms;
import antlab.scoring.TurnContributionCalculator.Values;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hand-computed turn contribution: integer money, one ratio rounding, and the report's benchmark code. */
class TurnContributionCalculatorTest {

    private static final SeasonTerms SEASON = new SeasonTerms(7L, won(1_000_000), new BigDecimal("0.00015000"),
            new BigDecimal("0.00250000"), new BigDecimal("0.00400000"));

    /*
     * Benchmark by hand: KR leg 500,000 / (10,000 x 1.00015) -> 49 shares, fee round(73.5) = 74; US leg
     * 500,000 / (100,000 x 1.0065) -> 4 shares, fee 2,600. Cash left 1,000,000 - 490,074 - 402,600 = 107,326.
     * Turn 1 = 49 x 11,000 + 4 x 105,000 + 107,326 = 1,066,326; turn 2 = 49 x 9,900 + 4 x 102,000 + 107,326 = 1,000,426.
     */
    private static final Map<AssetKey, List<BigDecimal>> BENCH = Map.of(
            Phase0MarketBaseline.BENCH_KR, List.of(won(10_000), won(11_000), won(9_900)),
            Phase0MarketBaseline.BENCH_US, List.of(won(100_000), won(105_000), won(102_000)));

    private static final Values TURN_1 = new Values(won(1_000_000), won(350_000), won(650_000), won(200_000),
            BigDecimal.ZERO, won(150_000));
    // 1,000,000 + contribution 5,000 - trading cost 120; positions from the three lines below, goalkeeper the residual
    private static final Values TURN_2 = new Values(won(1_004_880), won(360_000), won(649_880), won(220_000),
            BigDecimal.ZERO, won(135_000));

    private static final List<Line> LINES = List.of(
            new Line(1L, 10, won(20_000), won(22_000), 1, Position.DEFENDER),
            new Line(2L, 3, won(50_000), won(45_000), 1, Position.FORWARD_TARGET),
            new Line(3L, 5, won(1_000), won(1_000), 1, Position.GOALKEEPER));

    private static BigDecimal won(long value) {
        return BigDecimal.valueOf(value).setScale(4);
    }

    private static List<BigDecimal> prices(long... values) {
        return java.util.Arrays.stream(values).mapToObj(TurnContributionCalculatorTest::won).toList();
    }

    private static TurnContributionDto turnTwo() {
        return TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, LINES, won(120), BENCH);
    }

    @Test
    void holdings_carry_quantity_times_price_change_and_sort_by_contribution() {
        TurnContributionDto dto = turnTwo();

        assertThat(dto.holdings()).extracting(HoldingContributionDto::assetId).containsExactly(1L, 3L, 2L);
        HoldingContributionDto first = dto.holdings().get(0);
        assertThat(first.quantity()).isEqualTo(10);
        assertThat(first.previousPrice()).hasToString("20000");
        assertThat(first.price()).hasToString("22000");
        assertThat(first.value()).hasToString("220000");
        assertThat(first.contribution()).hasToString("20000");
        assertThat(first.priceReturn()).hasToString("0.10000000");
        assertThat(first.contributionReturn()).hasToString("0.02000000");
        HoldingContributionDto last = dto.holdings().get(2);
        assertThat(last.declaredPosition()).isEqualTo(Position.FORWARD_TARGET);
        assertThat(last.contribution()).hasToString("-15000");
        assertThat(last.priceReturn()).hasToString("-0.10000000");
        assertThat(last.contributionReturn()).hasToString("-0.01500000");
    }

    @Test
    void the_value_change_is_the_price_effect_minus_the_trading_cost() {
        TurnContributionDto dto = turnTwo();

        assertThat(dto.previousTotalValue()).hasToString("1000000");
        assertThat(dto.totalValue()).hasToString("1004880");
        assertThat(dto.valueChange()).hasToString("4880");
        assertThat(dto.holdingsContribution()).hasToString("5000");
        assertThat(dto.tradingCost()).hasToString("120");
        assertThat(dto.valueChange()).isEqualByComparingTo(dto.holdingsContribution().subtract(dto.tradingCost()));
        assertThat(dto.totalReturn()).hasToString("0.00488000");
    }

    @Test
    void positions_split_stored_values_from_the_price_effect() {
        PositionContributionsDto p = turnTwo().positions();

        assertThat(p.defender().value()).hasToString("220000");
        assertThat(p.defender().previousValue()).hasToString("200000");
        assertThat(p.defender().valueChange()).hasToString("20000");
        assertThat(p.defender().contribution()).hasToString("20000");
        assertThat(p.defender().contributionReturn()).hasToString("0.02000000");
        // A goalkeeper-declared asset that did not move adds 0; the fee left the cash, so the stored value fell
        assertThat(p.goalkeeper().contribution()).hasToString("0");
        assertThat(p.goalkeeper().valueChange()).hasToString("-120");
        assertThat(p.midfielder().contribution()).hasToString("0");
        assertThat(p.forward().contribution()).hasToString("-15000");
        assertThat(p.forward().valueChange()).hasToString("-15000");
    }

    @Test
    void the_benchmark_runs_to_this_turn_with_excess_divided_once() {
        BenchmarkToDateDto b = turnTwo().benchmark();

        assertThat(b.value()).hasToString("1000426");
        assertThat(b.previousValue()).hasToString("1066326");
        assertThat(b.valueChange()).hasToString("-65900");
        assertThat(b.periodReturn()).hasToString("-0.06180099");
        assertThat(b.totalReturn()).hasToString("0.00042600");
        assertThat(b.excessReturn()).hasToString("0.00445400");
    }

    @Test
    void turn_one_starts_from_the_initial_capital_in_cash() {
        Values turn1 = new Values(won(999_000), won(200_000), won(799_000), won(200_000), BigDecimal.ZERO,
                BigDecimal.ZERO);
        List<Line> lines = List.of(new Line(1L, 10, won(20_000), won(20_000), 1, Position.DEFENDER));

        TurnContributionDto dto = TurnContributionCalculator.calculate(SEASON, 1, null, turn1, lines, won(1_000),
                Map.of(Phase0MarketBaseline.BENCH_KR, prices(10_000, 11_000), Phase0MarketBaseline.BENCH_US,
                        prices(100_000, 105_000)));

        assertThat(dto.previousTotalValue()).hasToString("1000000");
        assertThat(dto.positions().goalkeeper().previousValue()).hasToString("1000000");
        assertThat(dto.positions().defender().previousValue()).hasToString("0");
        assertThat(dto.valueChange()).hasToString("-1000");
        assertThat(dto.tradingCost()).hasToString("1000");
        // The benchmark's turn-1 return is also measured against the initial capital, not its post-fee turn 0
        assertThat(dto.benchmark().previousValue()).hasToString("1000000");
        assertThat(dto.benchmark().value()).hasToString("1066326");
        assertThat(dto.benchmark().periodReturn()).hasToString("0.06632600");
    }

    @Test
    void no_holdings_give_an_empty_list_and_zero_contribution() {
        Values allCash = new Values(won(1_000_000), BigDecimal.ZERO, won(1_000_000), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);

        TurnContributionDto dto = TurnContributionCalculator.calculate(SEASON, 2, allCash, allCash, List.of(),
                BigDecimal.ZERO, BENCH);

        assertThat(dto.holdings()).isEmpty();
        assertThat(dto.holdingsContribution()).hasToString("0");
        assertThat(dto.valueChange()).hasToString("0");
        assertThat(dto.benchmark().excessReturn()).hasToString("-0.00042600");
    }

    @Test
    void the_raw_input_benchmark_equals_the_report_baseline() {
        SeasonReportSources src = SeasonReportFixtures.threeTurns();
        SeasonFacts s = src.season();

        BigDecimal raw = Phase0MarketBaseline.valueAt(s.initialCash(), s.feeRateKr(), s.feeRateUs(), s.fxSpreadRate(),
                src.benchmarkPrices(), s.currentTurnNo());

        assertThat(raw).isEqualByComparingTo(SeasonReportCalculator.calculate(src).baselineFinalValue());
    }

    @Test
    void a_negative_quantity_is_refused() {
        List<Line> lines = List.of(new Line(1L, -1, won(20_000), won(22_000), 1, Position.DEFENDER));

        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, lines, won(120), BENCH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("누적 수량이 음수");
    }

    @Test
    void a_missing_turn_price_is_refused() {
        List<Line> lines = List.of(new Line(1L, 10, null, won(22_000), 1, Position.DEFENDER));

        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, lines, won(120), BENCH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("턴 가격이 없다");
    }

    @Test
    void a_holding_without_exactly_one_open_span_is_refused() {
        List<Line> none = List.of(new Line(1L, 10, won(20_000), won(22_000), 0, null));
        List<Line> two = List.of(new Line(1L, 10, won(20_000), won(22_000), 2, Position.DEFENDER));

        for (List<Line> lines : List.of(none, two)) {
            assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, lines, won(120),
                    BENCH)).isInstanceOf(IllegalStateException.class).hasMessageContaining("선언 구간이 하나가 아니다");
        }
    }

    @Test
    void a_change_that_is_not_contribution_minus_trading_cost_is_refused_without_values() {
        // One won more cost than the stored change explains, as an unmodelled deposit or adjustment would cause
        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, LINES, won(121), BENCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("턴 기여 원천이 모순이다: 평가액 변화가 기여 합 − 매매 비용과 다르다");
    }

    @Test
    void rebuilt_holdings_that_differ_from_the_stored_turn_row_are_refused() {
        // Position sums match nothing the stored row says: the defender line alone is 220,000 but the row has 999
        Values wrong = new Values(won(1_004_880), won(360_000), won(649_880), won(999), BigDecimal.ZERO, won(135_000));

        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, wrong, LINES, won(120), BENCH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("턴 스냅샷과 다르다");
    }

    @Test
    void a_benchmark_proxy_without_prices_up_to_the_turn_is_refused() {
        Map<AssetKey, List<BigDecimal>> shortSeries = Map.of(Phase0MarketBaseline.BENCH_KR, prices(10_000, 11_000),
                Phase0MarketBaseline.BENCH_US, prices(100_000, 105_000, 102_000));

        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, LINES, won(120),
                shortSeries)).isInstanceOf(IllegalStateException.class).hasMessageContaining("기준선 대용 종목");
        assertThatThrownBy(() -> TurnContributionCalculator.calculate(SEASON, 2, TURN_1, TURN_2, LINES, won(120),
                Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("기준선 대용 종목");
    }
}
