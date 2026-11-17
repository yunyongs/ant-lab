package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import antlab.engine.client.EnginePercentileRequest;
import antlab.projection.PricedHolding;
import antlab.projection.ProjectionInput;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure steps between stored season facts and the engine request, with values worked by hand. No Spring, no database.
 * Team: cash 100 000, 100 x KR:069500 at 5 000 (500 000), 4 x US:SPY at 100 000 (400 000), total 1 000 000, so the shares are
 * 0.5, 0.4 and cash 0.1. The same holdings are worth 1 250 000 at the last turn: end multiple 1.25.
 */
class ScoreAssemblerTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static SeasonReportSources sources(String initialCash, long... totals) {
        SeasonFacts facts = new SeasonFacts(SeasonStatus.FINISHED, totals.length, totals.length, d(initialCash), d("-0.20"),
                d("0.00015"), d("0.0025"), d("0.004"), 10, 30, 30, 30);
        List<TurnFact> turns = new ArrayList<>();
        for (int i = 0; i < totals.length; i++) {
            BigDecimal total = BigDecimal.valueOf(totals[i]);
            turns.add(new TurnFact(i + 1, total, BigDecimal.ZERO, total, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return new SeasonReportSources(facts, turns, List.of(), null, List.of(), List.of(), Map.of(), Map.of(), List.of(), null);
    }

    private static ProjectionInput team() {
        return ProjectionInput.of(d("100000"), List.of(new PricedHolding("KR:069500", 100, d("5000")),
                new PricedHolding("US:SPY", 4, d("100000"))));
    }

    private static ScoreBasis basis(SeasonReportSources src, String startEndValue) {
        return new ScoreBasis(7L, 3L, -4611686018427387904L, LocalDate.of(2026, 1, 2), 12, false, src, null, team(),
                d(startEndValue));
    }

    @Test
    void team_returns_use_the_initial_cash_as_the_first_base() {
        List<BigDecimal> returns = ScoreAssembler.teamReturns(sources("1000000", 1_100_000, 990_000, 1_089_000));

        assertThat(returns).hasSize(3);
        assertThat(returns.get(0)).isEqualByComparingTo("0.1");
        assertThat(returns.get(1)).isEqualByComparingTo("-0.1");
        assertThat(returns.get(2)).isEqualByComparingTo("0.1");
    }

    @Test
    void a_team_build_fee_counts_in_the_first_return() {
        // value after turn 1 is below the initial cash because of the build fee: the first return is negative
        List<BigDecimal> returns = ScoreAssembler.teamReturns(sources("1000000", 999_000, 999_000));

        assertThat(returns.get(0)).isEqualByComparingTo("-0.001");
        assertThat(returns.get(1)).isEqualByComparingTo("0");
    }

    @Test
    void the_static_end_multiple_is_the_start_team_held_to_the_last_turn() {
        SeasonReportSources src = sources("1000000", 1_100_000, 990_000, 1_089_000, 980_100);
        List<BigDecimal> bench = List.of(d("0.02"), d("0.02"), d("0.04"), d("0.04"));

        ScoreAssembler.Observation o = ScoreAssembler.observe(basis(src, "1250000"), bench);

        assertThat(o.staticEndMultiple()).isEqualByComparingTo("1.25");
        // team: 0.1, -0.1, 0.1, -0.1 -> mean 0; benchmark: mean 0.03, sample deviation 0.01 x sqrt(4/3)
        assertThat(o.sharpe()).isEqualByComparingTo("0");
        assertThat(o.benchSharpe().setScale(12, RoundingMode.HALF_UP)).isEqualByComparingTo("2.598076211353");
        assertThat(o.sharpeDiff()).isEqualByComparingTo(o.sharpe().subtract(o.benchSharpe()));
    }

    @Test
    void the_request_carries_start_shares_the_benchmark_and_decimal_strings() {
        SeasonReportSources src = sources("1000000", 1_100_000, 990_000, 1_089_000, 980_100);
        ScoreAssembler.Observation o = ScoreAssembler.observe(basis(src, "1250000"),
                List.of(d("0.02"), d("0.02"), d("0.04"), d("0.04")));

        EnginePercentileRequest request = ScoreAssembler.request(basis(src, "1250000"), o, 2000);

        assertThat(request.seasonSeed()).isEqualTo("-4611686018427387904");
        assertThat(request.baseDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(request.horizonTurns()).isEqualTo(12);
        assertThat(request.n()).isEqualTo(2000);
        assertThat(request.team().cash()).isEqualTo("0.100000000000");
        assertThat(request.team().weights()).extracting("asset", "weight")
                .containsExactly(tuple("KR:069500", "0.500000000000"),
                        tuple("US:SPY", "0.400000000000"));
        assertThat(request.benchmark().cash()).isEqualTo("0");
        assertThat(request.benchmark().weights()).extracting("asset", "weight")
                .containsExactly(tuple("KR:069500", "0.5"),
                        tuple("US:SPY", "0.5"));
        assertThat(request.observed().endMultiple()).isEqualTo("1.250000000000");
        assertThat(request.observed().sharpeDiff()).isEqualTo("-2.598076211353");
    }

    @Test
    void a_huge_sharpe_difference_is_capped_to_what_the_wire_holds() {
        // a deviation of 1e-26 around 0.01 makes the Sharpe value about 1e24, far beyond six integer digits
        BigDecimal tiny = new BigDecimal("0.010000000000000000000000001");
        List<BigDecimal> bench = List.of(d("0.01"), tiny, d("0.01"), tiny);
        SeasonReportSources src = sources("1000000", 1_100_000, 990_000, 1_089_000, 980_100);
        ScoreAssembler.Observation o = ScoreAssembler.observe(basis(src, "1250000"), bench);

        EnginePercentileRequest request = ScoreAssembler.request(basis(src, "1250000"), o, 2000);

        assertThat(o.benchSharpe().abs()).isGreaterThan(d("1000000"));
        assertThat(o.sharpeDiff().signum()).isNegative();
        assertThat(request.observed().sharpeDiff()).isEqualTo("-999999.999999999999");
    }

    @Test
    void the_end_multiple_is_rounded_once_at_twelve_digits() {
        SeasonReportSources src = sources("1000000", 1_100_000, 990_000, 1_089_000, 980_100);
        // 1 000 000 / 3 shares of value: 1 234 567.8901234567 / 1 000 000 would not fit twelve digits unrounded
        ScoreAssembler.Observation o = ScoreAssembler.observe(basis(src, "1234567.890123656789"),
                List.of(d("0.02"), d("0.02"), d("0.04"), d("0.04")));

        EnginePercentileRequest request = ScoreAssembler.request(basis(src, "1234567.890123656789"), o, 2000);

        assertThat(request.observed().endMultiple()).isEqualTo("1.234567890124");
    }

    @Test
    void an_unsupported_horizon_is_refused_before_a_request_exists() {
        SeasonReportSources src = sources("1000000", 1_100_000, 990_000);
        ScoreBasis odd = new ScoreBasis(7L, 3L, 14L, LocalDate.of(2026, 1, 2), 36, false, src, null, team(), d("1250000"));
        ScoreAssembler.Observation o = ScoreAssembler.observe(odd, List.of(d("0.02"), d("0.04")));

        assertThat(EnginePercentileRequest.supportsHorizon(36)).isFalse();
        assertThatThrownBy(() -> ScoreAssembler.request(odd, o, 2000)).isInstanceOf(IllegalArgumentException.class);
    }
}
