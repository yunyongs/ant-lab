package antlab.scoring;

import static antlab.scoring.SeasonReportCalculatorTest.C_ASSET;
import static antlab.scoring.SeasonReportCalculatorTest.eightTurns;
import static antlab.scoring.SeasonReportCalculatorTest.roleHoldings;
import static antlab.scoring.SeasonReportFixtures.A;
import static antlab.scoring.SeasonReportFixtures.B;
import static antlab.scoring.SeasonReportFixtures.KR;
import static antlab.scoring.SeasonReportFixtures.US;
import static antlab.scoring.SeasonReportFixtures.holding;
import static antlab.scoring.SeasonReportFixtures.prices;
import static antlab.scoring.SeasonReportFixtures.bd;
import static antlab.scoring.SeasonReportFixtures.noOrders;
import static antlab.scoring.SeasonReportFixtures.returns;
import static antlab.scoring.SeasonReportFixtures.turn;
import static antlab.scoring.SeasonReportFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.season.SeasonStatus;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.SeasonReportSources.ReconFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Formation adherence on season-measured positions. The 8-turn scenario of {@link SeasonReportCalculatorTest}
 * measures A as goalkeeper and B as defender and cannot measure C, so every expected value below is known by hand.
 * Expected numbers come from an independent exact-fraction computation (Python {@code Fraction}), not from this code.
 * Target 10/20/30/40 (every class differs, so swapping two classes changes the value once a class exceeds its target) unless a test says otherwise; turn total = 30,000,000 + 100,000·t.
 */
class SeasonReportMeasuredDeviationTest {

    private static final int[] TARGET = {10, 20, 30, 40};

    private static SeasonReportSources withTarget(SeasonReportSources s) {
        SeasonFacts f = s.season();
        SeasonFacts season = new SeasonFacts(f.status(), f.pathLength(), f.currentTurnNo(), f.initialCash(), f.riskLimit(),
                f.feeRateKr(), f.feeRateUs(), f.fxSpreadRate(), TARGET[0], TARGET[1], TARGET[2], TARGET[3]);
        return SeasonReportFixtures.withSeason(s, season);
    }

    private static List<HoldingFact> holdings(Position cSystem, long cValue) {
        List<HoldingFact> out = new ArrayList<>();
        for (HoldingFact h : roleHoldings()) {
            out.add(h.asset().equals(C_ASSET) ? new HoldingFact(C_ASSET, h.turnNo(), won(cValue), cSystem) : h);
        }
        return out;
    }

    private static SeasonReportDto calc(SeasonReportSources s) {
        return SeasonReportCalculator.calculate(s);
    }

    private static SeasonReportSources scenario(Position declaredA, Position declaredB, List<HoldingFact> holdings) {
        SeasonReportSources base = withTarget(eightTurns(declaredA, declaredB, holdings));
        return SeasonReportFixtures.withHoldings(base, holdings, base.lineup());
    }

    @Test
    void 실측_포지션으로_묶은_이탈이_손_계산과_같다() {
        // A measured goalkeeper (residual), B measured defender 500,000 on every turn, C unmeasured: system forward 300,000 on turn 3
        SeasonReportDto dto = calc(scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.FORWARD_TARGET, 300_000)));
        assertThat(dto.measuredFormationDeviation().toPlainString()).isEqualTo("1.76468217");
    }

    @Test
    void 선언을_바꿔도_실측_이탈은_그대로이고_선언_기준_이탈과_다르다() {
        List<HoldingFact> h = holdings(Position.FORWARD_TARGET, 300_000);
        SeasonReportDto right = calc(scenario(Position.GOALKEEPER, Position.DEFENDER, h));
        SeasonReportDto relabelled = calc(scenario(Position.FORWARD_BREAKOUT, Position.MIDFIELDER, h));
        assertThat(relabelled.measuredFormationDeviation()).isEqualByComparingTo(right.measuredFormationDeviation());
        // the declared-basis item reads the stored snapshot, which these two sources share, so it cannot tell them apart;
        // the point is that the two measures are different quantities on the same source
        assertThat(right.measuredFormationDeviation()).isNotEqualByComparingTo(right.formationDeviation());
    }

    @Test
    void 실측_불능_종목은_선언이_아니라_기준일_판정으로_센다() {
        List<HoldingFact> h = holdings(Position.FORWARD_TARGET, 300_000);
        SeasonReportSources base = scenario(Position.GOALKEEPER, Position.DEFENDER, h);
        List<LineupSpan> declaredFw = List.of(new LineupSpan(A, Position.GOALKEEPER, 0, null),
                new LineupSpan(B, Position.DEFENDER, 0, null), new LineupSpan(C_ASSET, Position.FORWARD_BREAKOUT, 0, null));
        BigDecimal sameSystem = calc(SeasonReportFixtures.withHoldings(base, h, declaredFw)).measuredFormationDeviation();
        assertThat(sameSystem).isEqualByComparingTo(calc(base).measuredFormationDeviation());

        // the system judgement moves C to goalkeeper: its 300,000 joins the residual, same as holding only A and B (1.76715742)
        SeasonReportDto goalkeeper = calc(scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.GOALKEEPER, 300_000)));
        assertThat(goalkeeper.measuredFormationDeviation().toPlainString()).isEqualTo("1.76715742");
    }

    @Test
    void 세_종류의_공격수_판정은_모두_공격으로_센다() {
        for (Position forward : List.of(Position.FORWARD_TARGET, Position.FORWARD_BREAKOUT, Position.FORWARD_DEFENSIVE)) {
            SeasonReportDto dto = calc(scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(forward, 300_000)));
            assertThat(dto.measuredFormationDeviation().toPlainString()).as(forward.name()).isEqualTo("1.76468217");
        }
    }

    @Test
    void 보유가_없으면_현금만이라_골키퍼_비중이_일이다() {
        // |100V − 10V| + 3·|0 − 30V| = 180V per turn, over 100V
        SeasonReportDto dto = calc(scenario(Position.GOALKEEPER, Position.DEFENDER, List.of()));
        assertThat(dto.measuredFormationDeviation().toPlainString()).isEqualTo("1.80000000");
    }

    @Test
    void 전술_변경은_다음_턴부터_목표를_바꾼다() {
        SeasonReportSources base = scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.FORWARD_TARGET, 300_000));
        SeasonReportSources changed = new SeasonReportSources(base.season(), base.turns(),
                List.of(new ChangeFact(1, 3, 40, 10, 20, 30)), base.orders(), base.holdings(), base.lineup(),
                base.poolReturns(), base.benchmarkPrices(), base.changeCosts(), base.recon());
        assertThat(calc(changed).measuredFormationDeviation().toPlainString()).isEqualTo("1.38968217");
    }

    @Test
    void 시즌을_통째로_실측할_수_없으면_역할_수행은_널이고_이탈은_기준일_판정으로_나온다() {
        // every market month is up, so nothing has the 3 down months a measurement needs
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        String[] up = {"0.02", "0.03", "0.02", "0.04", "0.03", "0.02", "0.03", "0.05"};
        pool.put(KR, returns(up));
        pool.put(US, returns(up));
        pool.put(A, returns(up));
        pool.put(B, returns(up));
        pool.put(C_ASSET, returns(up));
        List<HoldingFact> h = List.of(new HoldingFact(B, 3, won(500_000), Position.DEFENDER),
                new HoldingFact(C_ASSET, 3, won(300_000), Position.DEFENDER));
        SeasonReportSources base = scenario(Position.GOALKEEPER, Position.DEFENDER, h);
        SeasonReportDto dto = calc(SeasonReportFixtures.withMarket(base, pool, base.benchmarkPrices()));
        assertThat(dto.roleFit()).isNull();
        assertThat(dto.measuredFormationDeviation()).isNotNull();
        // both held assets by system judgement: defender 800,000 on turn 3 only (the same as B alone, measured defender, in the 8-turn scenario)
        BigDecimal viaSystem = calc(scenario(Position.GOALKEEPER, Position.DEFENDER,
                List.of(new HoldingFact(B, 3, won(800_000), Position.DEFENDER)))).measuredFormationDeviation();
        assertThat(viaSystem.toPlainString()).isEqualTo("1.79339934");
    }

    @Test
    void 실측도_기준일_판정도_없는_보유는_거부한다() {
        List<HoldingFact> h = List.of(new HoldingFact(C_ASSET, 3, won(300_000), null));
        assertThatThrownBy(() -> calc(scenario(Position.GOALKEEPER, Position.DEFENDER, h)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("기준일 판정도 없다");
    }

    @Test
    void 실측_포지션별_보유_합이_총_평가액을_넘으면_거부한다() {
        List<HoldingFact> h = List.of(new HoldingFact(B, 1, won(31_000_000), Position.DEFENDER));
        assertThatThrownBy(() -> calc(scenario(Position.GOALKEEPER, Position.DEFENDER, h)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("총 평가액을 넘는다");
    }

    @Test
    void 모든_보유가_실측되고_선언이_같으면_선언_기준_이탈과_같다() {
        // B (measured defender, declared defender) 500,000 on every turn; A measured goalkeeper is cash-like.
        // The stored snapshot is built to match: defender 500,000, goalkeeper the rest.
        SeasonReportSources base = scenario(Position.GOALKEEPER, Position.DEFENDER, List.of());
        List<TurnFact> turns = new ArrayList<>();
        List<HoldingFact> h = new ArrayList<>();
        for (int t = 1; t <= 8; t++) {
            long total = 30_000_000L + 100_000L * t;
            turns.add(turn(t, total, "0.00000000", total - 500_000, 500_000, 0, 0));
            h.add(holding(B, t, won(500_000)));
        }
        SeasonReportSources s = SeasonReportFixtures.withHoldings(SeasonReportFixtures.withTurns(base, turns), h, base.lineup());
        SeasonReportDto dto = calc(s);
        assertThat(dto.measuredFormationDeviation()).isEqualByComparingTo(dto.formationDeviation());
        assertThat(dto.measuredFormationDeviation().toPlainString()).isEqualTo("1.76715742");
    }

    @Test
    void 목표를_넘는_큰_보유는_포지션마다_다른_목표와_비교된다() {
        // B (measured defender) is half of the team on every turn, above its 20% target: a defender/midfielder mix-up would show
        List<HoldingFact> bigDefender = new ArrayList<>();
        List<HoldingFact> bigForward = new ArrayList<>();
        for (int t = 1; t <= 8; t++) {
            bigDefender.add(holding(B, t, won(15_000_000)));
            if (t >= 2) {
                bigForward.add(new HoldingFact(C_ASSET, t, won(12_000_000), Position.FORWARD_TARGET));
            }
        }
        assertThat(calc(scenario(Position.GOALKEEPER, Position.DEFENDER, bigDefender)).measuredFormationDeviation().toPlainString())
                .isEqualTo("1.40000000");
        assertThat(calc(scenario(Position.GOALKEEPER, Position.DEFENDER, bigForward)).measuredFormationDeviation().toPlainString())
                .isEqualTo("1.11144580");
    }

    @Test
    void 전액_투자한_턴은_골키퍼_잔차가_영이어도_거부하지_않는다() {
        // turn 3 total is 30,300,000: B (measured defender) is all of it, so the goalkeeper residual is exactly 0
        List<HoldingFact> h = List.of(holding(B, 3, won(30_300_000)));
        assertThat(calc(scenario(Position.GOALKEEPER, Position.DEFENDER, h)).measuredFormationDeviation().toPlainString())
                .isEqualTo("1.77500000");
    }

    @Test
    void 반올림은_여덟째_자리에서_한_번_0에서_먼_쪽으로_한다() {
        // One turn, total 2,000,000,000, defender holding 5 won, target 100/0/0/0: exact deviation 2·5 ÷ 2,000,000,000 = 0.000000005
        SeasonFacts season = new SeasonFacts(SeasonStatus.FINISHED, 1, 1, won(2_000_000_000), bd("-0.50000000"),
                bd("0.00100000"), bd("0.00200000"), bd("0.00400000"), 100, 0, 0, 0);
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        pool.put(KR, returns("0.01"));
        pool.put(US, returns("0.01"));
        pool.put(A, returns("0.01"));
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        bench.put(KR, prices(30_000, 30_000));
        bench.put(US, prices(70_000, 70_000));
        SeasonReportSources s = new SeasonReportSources(season,
                List.of(turn(1, 2_000_000_000L, "0.00000000", 1_999_999_995L, 5, 0, 0)), List.of(), noOrders(),
                List.of(new HoldingFact(A, 1, won(5), Position.DEFENDER)),
                List.of(new LineupSpan(A, Position.DEFENDER, 0, null)), pool, bench, List.of(), new ReconFacts(0, 1, 0, 0));
        assertThat(calc(s).measuredFormationDeviation().toPlainString()).isEqualTo("0.00000001");
    }

    @Test
    void 보고_턴_뒤의_경로_수익률이_섞이면_거부한다() {
        // a pool return of turn T + 1 would let the measurement see a month the report must not know
        SeasonReportSources base = scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.FORWARD_TARGET, 300_000));
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        base.poolReturns().forEach((key, value) -> {
            List<BigDecimal> longer = new ArrayList<>(value);
            longer.add(new BigDecimal("0.01"));
            pool.put(key, longer);
        });
        assertThatThrownBy(() -> calc(SeasonReportFixtures.withMarket(base, pool, base.benchmarkPrices())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("경로 수익률");
    }

    private static ScoreInputs scoreInputs(SeasonReportSources src, SeasonReportDto report) {
        List<BigDecimal> flat = new ArrayList<>();
        for (int i = 0; i < report.reportTurns(); i++) {
            flat.add(new BigDecimal(i % 2 == 0 ? "0.010000" : "-0.004000"));
        }
        ScoreAssembler.Observation observation = new ScoreAssembler.Observation(flat, flat, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE);
        ScoreBasis basis = new ScoreBasis(1L, 1L, 1L, java.time.LocalDate.of(2026, 1, 2), 8, false, src, null, null, null,
                BigDecimal.ONE);
        return ScoreAssembler.inputs(report, observation, basis, null, null);
    }

    @Test
    void 점수_입력의_포메이션_항목은_실측_이탈이고_선언_기준_이탈이_아니다() {
        SeasonReportSources src = scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.FORWARD_TARGET, 300_000));
        SeasonReportDto report = calc(src);
        assertThat(report.measuredFormationDeviation()).isNotEqualByComparingTo(report.formationDeviation());
        assertThat(scoreInputs(src, report).measuredFormationDeviation()).isEqualByComparingTo(report.measuredFormationDeviation());
    }

    /** Target 94/3/1/2 keeps the measured deviation well under 0.50, so the formation score is not floored at zero. */
    private static SeasonReportSources lowDeviation(SeasonReportSources s) {
        SeasonFacts f = s.season();
        return SeasonReportFixtures.withSeason(s, new SeasonFacts(f.status(), f.pathLength(), f.currentTurnNo(), f.initialCash(),
                f.riskLimit(), f.feeRateKr(), f.feeRateUs(), f.fxSpreadRate(), 94, 3, 1, 2));
    }

    /** The stored snapshot groups the holdings of B and C by the declared position; the rest is goalkeeper. */
    private static SeasonReportSources declaredAs(SeasonReportSources s, boolean midfield) {
        List<TurnFact> turns = new ArrayList<>();
        for (TurnFact t : s.turns()) {
            long moved = 1_500_000L;
            long total = t.totalValue().longValueExact();
            turns.add(SeasonReportFixtures.turn(t.turnNo(), total, "0.00000000", total - moved, 0, midfield ? moved : 0,
                    midfield ? 0 : moved));
        }
        return SeasonReportFixtures.withTurns(s, turns);
    }

    @Test
    void 선언만_바꾼_두_성적표는_선언_기준_이탈이_다르고_포메이션_점수는_같다() {
        SeasonReportSources base = lowDeviation(scenario(Position.GOALKEEPER, Position.DEFENDER,
                holdings(Position.FORWARD_TARGET, 300_000)));
        SeasonReportSources asMidfield = declaredAs(base, true);
        SeasonReportSources asForward = declaredAs(base, false);
        SeasonReportDto r1 = calc(asMidfield);
        SeasonReportDto r2 = calc(asForward);
        assertThat(r1.formationDeviation()).as("the declared basis moves").isNotEqualByComparingTo(r2.formationDeviation());
        assertThat(r1.measuredFormationDeviation()).isEqualByComparingTo(r2.measuredFormationDeviation());
        BigDecimal s1 = SeasonScoreCalculator.calculate(scoreInputs(asMidfield, r1)).formationScore();
        BigDecimal s2 = SeasonScoreCalculator.calculate(scoreInputs(asForward, r2)).formationScore();
        assertThat(s1).as("not floored").isGreaterThan(BigDecimal.ZERO);
        assertThat(s1).isEqualByComparingTo(s2);
    }

    @Test
    void 입력_기록은_채점한_실측_값과_참고용_선언_값을_둘_다_남긴다() {
        SeasonReportSources src = scenario(Position.GOALKEEPER, Position.DEFENDER, holdings(Position.FORWARD_TARGET, 300_000));
        SeasonReportDto report = calc(src);
        ScoreAssembler.Observation observation = new ScoreAssembler.Observation(List.of(), List.of(), BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        ScoreBasis basis = new ScoreBasis(1L, 1L, 1L, java.time.LocalDate.of(2026, 1, 2), 8, false, src, null, null, null,
                BigDecimal.ONE);
        tools.jackson.databind.JsonNode node = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(ScoreInputsJson.of(report, observation, basis, null, null, null));
        assertThat(node.at("/report/measuredFormationDeviation").asString())
                .isEqualTo(report.measuredFormationDeviation().toPlainString());
        assertThat(node.at("/report/formationDeviation").asString()).isEqualTo(report.formationDeviation().toPlainString());
        assertThat(node.at("/scoreVersion").asString()).isEqualTo("s63-v3");
    }
}
