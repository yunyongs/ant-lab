package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Hand-computed fixture for formula s63-v1. Worked values (independent decimal arithmetic, not this code):
 * <pre>
 * sharpe 0.34412360, bench 0.40414519, diff -0.06002159
 * items  risk 75 (3 of 12 turns, depth 0.05 of limit 0.20), discipline 70 (mean of 0.2 and 0.4), diversification 50
 *        (weight 0.30), turnover 75 (0.5), formation 80 (0.10), role 60, nudge 75; consistency and noise empty
 * process = 485 / 7 = 69.285714..., stored 69.29
 * result  = 100 x (1200 + 100 / 2) / 2000 = 62.5; score = 0.5 x 62.5 + 0.5 x 69.285714... = 65.892857..., stored 65.89
 * (rounding the parts first would give 0.5 x 62.50 + 0.5 x 69.29 = 65.895, stored 65.90: the fixture tells them apart)
 * </pre>
 */
class SeasonScoreCalculatorTest {

    static final List<BigDecimal> TEAM = decimals("0.02", "-0.01", "0.03", "0.01", "-0.02", "0.04", "0.00", "0.01",
            "-0.01", "0.02", "0.03", "-0.03");
    static final List<BigDecimal> BENCH = decimals("0.01", "0.00", "0.02", "0.01", "-0.01", "0.03", "0.01", "0.00",
            "-0.01", "0.01", "0.02", "-0.02");

    static List<BigDecimal> decimals(String... values) {
        return Stream.of(values).map(BigDecimal::new).toList();
    }

    static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /** The worked fixture; tests change one field at a time through the builder. */
    static Builder fixture() {
        return new Builder();
    }

    static final class Builder {
        List<BigDecimal> returns = TEAM;
        List<BigDecimal> bench = BENCH;
        int reportTurns = 12;
        Integer breachTurns = 3;
        BigDecimal depth = d("-0.05");
        BigDecimal limit = d("-0.20");
        BigDecimal weight = d("0.30");
        BigDecimal turnover = d("0.5");
        BigDecimal formation = d("0.10");
        BigDecimal role = d("0.6");
        BigDecimal nudge = d("0.25");
        DisciplineFacts discipline = new DisciplineFacts(d("0.2"), d("0.4"));
        RankCounts diffCounts = new RankCounts(1200, 100, 2000);
        RankCounts endCounts = new RankCounts(900, 0, 2000);

        ScoreInputs build() {
            return new ScoreInputs(returns, bench, reportTurns, breachTurns, depth, limit, weight, turnover, formation,
                    role, nudge, discipline, diffCounts, endCounts);
        }

        SeasonScoreResult run() {
            return SeasonScoreCalculator.calculate(build());
        }
    }

    @Test
    void the_worked_fixture_gives_every_stored_value() {
        SeasonScoreResult r = fixture().run();

        assertThat(r.scoreVersion()).isEqualTo("s63-v1");
        assertThat(r.sharpe()).isEqualByComparingTo("0.34412360");
        assertThat(r.benchSharpe()).isEqualByComparingTo("0.40414519");
        assertThat(r.sharpeDiff()).isEqualByComparingTo("-0.06002159");
        assertThat(r.sharpeDiffPercentile()).isEqualByComparingTo("0.62500000");
        assertThat(r.luckPercentile()).isEqualByComparingTo("0.45000000");
        assertThat(r.riskScore()).isEqualByComparingTo("75.00");
        assertThat(r.disciplineScore()).isEqualByComparingTo("70.00");
        assertThat(r.diversificationScore()).isEqualByComparingTo("50.00");
        assertThat(r.turnoverScore()).isEqualByComparingTo("75.00");
        assertThat(r.consistencyScore()).isNull();
        assertThat(r.formationScore()).isEqualByComparingTo("80.00");
        assertThat(r.roleScore()).isEqualByComparingTo("60.00");
        assertThat(r.noiseScore()).isNull();
        assertThat(r.nudgeScore()).isEqualByComparingTo("75.00");
        assertThat(r.processItems()).isEqualTo(7);
        assertThat(r.processScore()).isEqualByComparingTo("69.29");
        assertThat(r.resultScore()).isEqualByComparingTo("62.50");
        assertThat(r.score()).isEqualByComparingTo("65.89");
        assertThat(r.score().scale()).isEqualTo(2);
        assertThat(r.sharpe().scale()).isEqualTo(8);
        assertThat(r.sharpeDiffPercentile().scale()).isEqualTo(8);
    }

    @Test
    void the_score_is_rounded_once_from_the_unrounded_parts() {
        SeasonScoreResult r = fixture().run();
        BigDecimal partsFirst = new BigDecimal("0.5").multiply(r.resultScore())
                .add(new BigDecimal("0.5").multiply(r.processScore()));

        assertThat(partsFirst).isEqualByComparingTo("65.895");
        assertThat(r.score()).isEqualByComparingTo("65.89").isNotEqualByComparingTo("65.90");
    }

    @Test
    void zero_deviation_returns_give_a_sharpe_of_zero_without_a_division_error() {
        Builder b = fixture();
        b.returns = Collections.nCopies(12, d("0.01"));
        b.bench = Collections.nCopies(12, d("0"));

        SeasonScoreResult r = b.run();

        assertThat(r.sharpe()).isEqualByComparingTo("0");
        assertThat(r.benchSharpe()).isEqualByComparingTo("0");
        assertThat(r.sharpeDiff()).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @CsvSource({
            // weight, diversification score
            "0.00, 100.00", "0.10, 100.00", "0.30, 50.00", "0.50, 0.00", "0.90, 0.00", "1.00, 0.00"})
    void diversification_is_full_up_to_ten_percent_and_zero_from_fifty_percent(String weight, String expected) {
        Builder b = fixture();
        b.weight = d(weight);

        assertThat(b.run().diversificationScore()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0, 100.00", "0.5, 75.00", "1, 50.00", "2, 0.00", "3.5, 0.00"})
    void turnover_reaches_zero_at_two_hundred_percent_and_stays_there(String turnover, String expected) {
        Builder b = fixture();
        b.turnover = d(turnover);

        assertThat(b.run().turnoverScore()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0, 100.00", "0.25, 50.00", "0.50, 0.00", "1.40, 0.00", "2, 0.00"})
    void formation_reaches_zero_at_a_deviation_of_half_and_stays_there(String deviation, String expected) {
        Builder b = fixture();
        b.formation = d(deviation);

        assertThat(b.run().formationScore()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            // breach turns, depth, expected risk (limit -0.20, 12 turns)
            "0, 0, 100.00", "12, -0.20, 0.00", "12, -0.90, 0.00", "6, -0.10, 50.00", "0, -0.40, 50.00"})
    void risk_combines_breach_turns_and_clamped_breach_depth(int turns, String depth, String expected) {
        Builder b = fixture();
        b.breachTurns = turns;
        b.depth = d(depth);

        assertThat(b.run().riskScore()).isEqualByComparingTo(expected);
    }

    @Test
    void role_and_nudge_follow_their_ratios_at_both_ends() {
        Builder low = fixture();
        low.role = d("0");
        low.nudge = d("1");
        Builder high = fixture();
        high.role = d("1");
        high.nudge = d("0");

        assertThat(low.run().roleScore()).isEqualByComparingTo("0.00");
        assertThat(low.run().nudgeScore()).isEqualByComparingTo("0.00");
        assertThat(high.run().roleScore()).isEqualByComparingTo("100.00");
        assertThat(high.run().nudgeScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void discipline_averages_only_the_measured_ratios() {
        Builder oneRatio = fixture();
        oneRatio.discipline = new DisciplineFacts(d("0.5"), null);
        Builder neither = fixture();
        neither.discipline = new DisciplineFacts(null, null);

        assertThat(oneRatio.run().disciplineScore()).isEqualByComparingTo("50.00");
        assertThat(neither.run().disciplineScore()).isNull();
    }

    @Test
    void a_null_input_makes_its_item_null_and_the_mean_runs_over_the_rest() {
        Builder b = fixture();
        b.discipline = null;
        b.role = null;
        b.nudge = null;

        SeasonScoreResult r = b.run();

        assertThat(r.disciplineScore()).isNull();
        assertThat(r.roleScore()).isNull();
        assertThat(r.nudgeScore()).isNull();
        assertThat(r.processItems()).isEqualTo(4);
        // (75 + 50 + 75 + 80) / 4 = 70
        assertThat(r.processScore()).isEqualByComparingTo("70.00");
    }

    @Test
    void a_single_remaining_item_still_gives_a_score() {
        Builder b = fixture();
        b.breachTurns = null;
        b.discipline = null;
        b.weight = null;
        b.turnover = null;
        b.formation = null;
        b.role = null;
        b.nudge = d("0.25");

        SeasonScoreResult r = b.run();

        assertThat(r.processItems()).isEqualTo(1);
        assertThat(r.processScore()).isEqualByComparingTo("75.00");
        assertThat(r.score()).isNotNull();
    }

    @Test
    void no_distribution_leaves_result_score_score_and_both_percentiles_null_together() {
        Builder b = fixture();
        b.diffCounts = null;
        b.endCounts = null;

        SeasonScoreResult r = b.run();

        assertThat(r.resultScore()).isNull();
        assertThat(r.score()).isNull();
        assertThat(r.sharpeDiffPercentile()).isNull();
        assertThat(r.luckPercentile()).isNull();
        assertThat(r.processScore()).isEqualByComparingTo("69.29");
    }

    @Test
    void rank_counts_must_come_together() {
        Builder b = fixture();
        b.endCounts = null;

        assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_exclusion_is_not_started_before_no_score_and_empty_for_a_normal_season() {
        SeasonScoreResult scored = fixture().run();
        Builder b = fixture();
        b.diffCounts = null;
        b.endCounts = null;
        SeasonScoreResult unscored = b.run();

        assertThat(SeasonScoreCalculator.exclusion(false, scored)).isNull();
        assertThat(SeasonScoreCalculator.exclusion(false, unscored)).isEqualTo(RatingExclusion.NO_SCORE);
        assertThat(SeasonScoreCalculator.exclusion(true, unscored)).isEqualTo(RatingExclusion.NOT_STARTED);
        assertThat(SeasonScoreCalculator.exclusion(true, scored)).isEqualTo(RatingExclusion.NOT_STARTED);
    }

    @Test
    void the_calculator_uses_only_decimal_arithmetic_for_the_result() {
        // 0.1 + 0.2 in binary floating point is 0.30000000000000004; the stored item must still be exact
        Builder b = fixture();
        b.weight = d("0.30");
        b.turnover = d("0.1").add(d("0.2"));

        assertThat(b.run().turnoverScore()).isEqualByComparingTo("85.00");
    }

    // ---- negatives

    @Test
    void zero_report_turns_are_refused() {
        Builder b = fixture();
        b.reportTurns = 0;
        b.returns = List.of();
        b.bench = List.of();

        assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void series_of_the_wrong_length_or_a_single_return_are_refused() {
        Builder shortSeries = fixture();
        shortSeries.returns = TEAM.subList(0, 11);
        Builder single = fixture();
        single.reportTurns = 1;
        single.returns = TEAM.subList(0, 1);
        single.bench = BENCH.subList(0, 1);
        single.breachTurns = 0;

        assertThatThrownBy(shortSeries::run).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(single::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_return_is_refused() {
        Builder b = fixture();
        b.returns = Stream.concat(TEAM.subList(0, 11).stream(), Stream.<BigDecimal>of((BigDecimal) null)).toList();

        assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"weight, 1.01", "weight, -0.01", "turnover, -0.5", "formation, 2.01", "role, 1.01", "role, -0.1",
            "nudge, 1.5", "nudge, -0.2"})
    void item_inputs_outside_their_range_are_refused(String field, String value) {
        Builder b = fixture();
        switch (field) {
            case "weight" -> b.weight = d(value);
            case "turnover" -> b.turnover = d(value);
            case "formation" -> b.formation = d(value);
            case "role" -> b.role = d(value);
            default -> b.nudge = d(value);
        }

        assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void risk_inputs_out_of_range_are_refused() {
        Builder tooManyBreaches = fixture();
        tooManyBreaches.breachTurns = 13;
        Builder positiveDepth = fixture();
        positiveDepth.depth = d("0.01");
        Builder zeroLimit = fixture();
        zeroLimit.limit = d("0");

        assertThatThrownBy(tooManyBreaches::run).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(positiveDepth::run).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(zeroLimit::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void all_items_missing_is_refused_because_the_column_needs_at_least_one() {
        Builder b = fixture();
        b.breachTurns = null;
        b.discipline = null;
        b.weight = null;
        b.turnover = null;
        b.formation = null;
        b.role = null;
        b.nudge = null;

        assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discipline_ratios_outside_zero_to_one_are_refused() {
        assertThatThrownBy(() -> new DisciplineFacts(d("1.1"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DisciplineFacts(null, d("-0.1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
