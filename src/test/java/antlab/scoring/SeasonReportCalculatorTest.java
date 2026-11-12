package antlab.scoring;

import static antlab.scoring.SeasonReportFixtures.A;
import static antlab.scoring.SeasonReportFixtures.B;
import static antlab.scoring.SeasonReportFixtures.KR;
import static antlab.scoring.SeasonReportFixtures.US;
import static antlab.scoring.SeasonReportFixtures.bd;
import static antlab.scoring.SeasonReportFixtures.noOrders;
import static antlab.scoring.SeasonReportFixtures.prices;
import static antlab.scoring.SeasonReportFixtures.returns;
import static antlab.scoring.SeasonReportFixtures.threeTurns;
import static antlab.scoring.SeasonReportFixtures.turn;
import static antlab.scoring.SeasonReportFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.OrderFacts;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.ReconFacts;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import antlab.season.FormationCost;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 성적표 계산기 — 손 원천으로 37값을 확인한다. 기대값은 파이썬 {@code Decimal}·{@code Fraction} 으로 따로 계산했다(같은 코드를 다시 쓰지 않았다).
 * 원천 조회가 거르는 것(0턴 팀 구성 제외 등)은 여기서 보지 않는다 — 그 값은 {@link SeasonReportSources.OrderFacts} 로 들어온다.
 */
class SeasonReportCalculatorTest {

    private static SeasonReportDto calc(SeasonReportSources s) {
        return SeasonReportCalculator.calculate(s);
    }

    @Test
    void 응답은_서른일곱_칸이다() {
        assertThat(SeasonReportDto.class.getRecordComponents()).hasSize(37);
    }

    @Test
    void 세_턴_끝난_시즌의_값을_손_계산과_맞춘다() {
        SeasonReportDto dto = calc(threeTurns());
        assertThat(dto.seasonStatus()).isEqualTo(SeasonStatus.FINISHED);
        assertThat(dto.isFinal()).isTrue();
        assertThat(dto.reportTurns()).isEqualTo(3);
        assertThat(dto.pathLength()).isEqualTo(3);
        assertThat(dto.pendingOrders()).isZero();
        assertThat(dto.pendingChanges()).isZero();
        assertThat(dto.initialCash().toPlainString()).isEqualTo("30000000");
        assertThat(dto.finalValue().toPlainString()).isEqualTo("30900002");
        assertThat(dto.totalReturn().toPlainString()).isEqualTo("0.03000007");
        assertThat(dto.monthlyVolatility().toPlainString()).isEqualTo("0.03010355");
        assertThat(dto.maxDrawdown().toPlainString()).isEqualTo("-0.01980198");
        assertThat(dto.baselineFinalValue().toPlainString()).isEqualTo("32884069");
        assertThat(dto.baselineReturn().toPlainString()).isEqualTo("0.09613563");
        assertThat(dto.excessReturn().toPlainString()).isEqualTo("-0.06613557");
        assertThat(dto.riskLimit().toPlainString()).isEqualTo("-0.01000000");
        assertThat(dto.riskBreachTurns()).isEqualTo(1);
        assertThat(dto.riskBreachDepth().toPlainString()).isEqualTo("-0.00980198");
        assertThat(dto.maxSingleWeight().toPlainString()).isEqualTo("0.12944983");
        assertThat(dto.tradeNotional().toPlainString()).isEqualTo("12000000");
        assertThat(dto.turnoverAnnual().toPlainString()).isEqualTo("1.58415838");
        assertThat(dto.formationDeviation().toPlainString()).isEqualTo("0.20206162");
        assertThat(dto.nudgeShare().toPlainString()).isEqualTo("0.25000000");
        assertThat(dto.nudgeSameTurnShare().toPlainString()).isEqualTo("0.10000000");
        assertThat(dto.totalFee().toPlainString()).isEqualTo("60000");
        assertThat(dto.teamFee().toPlainString()).isEqualTo("40000");
        assertThat(dto.platformNudgeFee().toPlainString()).isEqualTo("5000");
        assertThat(dto.formationChangeFee().toPlainString()).isEqualTo("1700");
        assertThat(dto.formationChanges()).isEqualTo(3);
        assertThat(dto.formationChangeOrders()).isEqualTo(3);
        assertThat(dto.changesWithoutOrders()).isEqualTo(1);
        assertThat(dto.reconMismatchRows()).isEqualTo(2);
        assertThat(dto.reconTurnBundles()).isEqualTo(3);
        assertThat(dto.reconManualBundles()).isEqualTo(1);
        assertThat(dto.reconOutOfRangeRows()).isZero();
    }

    @Test
    void 초과_수익률은_한_번_나누며_두_수익률의_차와_다를_수_있다() {
        SeasonReportDto dto = calc(threeTurns());
        // 0.03000007 − 0.09613563 = −0.06613556 이지만 (최종 − 기준선) ÷ 초기 = −0.06613557
        assertThat(dto.totalReturn().subtract(dto.baselineReturn()).toPlainString()).isEqualTo("-0.06613556");
        assertThat(dto.excessReturn().toPlainString()).isEqualTo("-0.06613557");
    }

    @Test
    void 기준선은_정수_주만_사고_남은_현금을_더한다() {
        // 국내 대용 499주(30,000원), 미국 대용 213주(70,000원), 수수료를 낸 뒤 남은 현금 15,570원 → 3턴 평가액
        // 499 × 33,001 + 213 × 77,000 + 15,570 = 32,884,069
        assertThat(calc(threeTurns()).baselineFinalValue().toPlainString()).isEqualTo("32884069");
        // 대용의 마지막 가격만 바꾸면 그 가격 × 수량만큼 달라진다(다른 가격은 마지막 평가에 쓰이지 않는다): 499 × 34,000 + 213 × 77,000 + 15,570
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        bench.put(KR, prices(30_000, 1, 1, 34_000));
        bench.put(US, prices(70_000, 1, 1, 77_000));
        SeasonReportSources s = SeasonReportFixtures.withMarket(threeTurns(), threeTurns().poolReturns(), bench);
        assertThat(calc(s).baselineFinalValue().toPlainString()).isEqualTo("33382570");
    }

    @Test
    void 같은_턴_전술_변경_둘은_id가_큰_쪽이_다음_턴_목표다() {
        SeasonReportSources base = threeTurns();
        assertThat(calc(base).formationDeviation().toPlainString()).isEqualTo("0.20206162");
        // 1턴에 낸 첫 변경(10/40/30/20)만 있으면 2턴 목표가 달라져 값이 갈린다
        List<ChangeFact> onlyFirst = new ArrayList<>(base.changes());
        onlyFirst.remove(1);
        SeasonReportSources s = SeasonReportFixtures.withChanges(base, onlyFirst, base.changeCosts());
        assertThat(calc(s).formationDeviation().toPlainString()).isEqualTo("0.18028833");
    }

    @Test
    void 전술_변경이_하나도_없으면_시작_포메이션이_목표다() {
        SeasonReportSources base = threeTurns();
        SeasonReportSources s = SeasonReportFixtures.withChanges(base, List.of(), List.of());
        SeasonReportDto dto = calc(s);
        assertThat(dto.formationChanges()).isZero();
        assertThat(dto.formationChangeOrders()).isZero();
        assertThat(dto.changesWithoutOrders()).isZero();
        assertThat(dto.formationChangeFee().toPlainString()).isEqualTo("0");
        // 1턴은 시작 포메이션과 정확히 같아 이탈 0 — 값이 0이 아니면 목표가 잘못 잡힌 것이다
        assertThat(dto.formationDeviation().signum()).isPositive();
    }

    @Test
    void 보고_범위_밖의_전술_변경_비용은_세지_않는다() {
        SeasonReportSources base = threeTurns();
        List<FormationCost> costs = new ArrayList<>(base.changeCosts());
        costs.add(new FormationCost(14, 3, 4, won(1), won(1), won(999), won(0)));
        SeasonReportSources s = SeasonReportFixtures.withChanges(base, base.changes(), costs);
        SeasonReportDto dto = calc(s);
        assertThat(dto.formationChanges()).isEqualTo(3);
        assertThat(dto.formationChangeOrders()).isEqualTo(3);
        assertThat(dto.formationChangeFee().toPlainString()).isEqualTo("1700");
    }

    @Test
    void 위반이_없으면_위험_위반_깊이는_영이다() {
        SeasonReportSources base = threeTurns();
        SeasonReportSources s = SeasonReportFixtures.withSeason(base,
                SeasonReportFixtures.seasonWith(base.season(), SeasonStatus.FINISHED, 3, 3, "-0.50000000"));
        SeasonReportDto dto = calc(s);
        assertThat(dto.riskBreachTurns()).isZero();
        assertThat(dto.riskBreachDepth().toPlainString()).isEqualTo("0.00000000");
        // 낙폭이 선언과 정확히 같으면 위반이 아니다
        SeasonReportSources equal = SeasonReportFixtures.withSeason(base,
                SeasonReportFixtures.seasonWith(base.season(), SeasonStatus.FINISHED, 3, 3, "-0.01980198"));
        assertThat(calc(equal).riskBreachTurns()).isZero();
        assertThat(calc(equal).riskBreachDepth().toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 보유가_없으면_최대_단일_비중은_영이고_역할_수행은_없다() {
        SeasonReportSources s = SeasonReportFixtures.withHoldings(threeTurns(), List.of(), List.of());
        SeasonReportDto dto = calc(s);
        assertThat(dto.maxSingleWeight().toPlainString()).isEqualTo("0.00000000");
        assertThat(dto.roleFit()).isNull();
        assertThat(dto.rolePlayersJudged()).isZero();
        assertThat(dto.rolePlayersUnjudged()).isZero();
    }

    @Test
    void 매매_대금이_영이면_독려_비중은_널이다() {
        SeasonReportSources s = SeasonReportFixtures.withOrders(threeTurns(), noOrders());
        SeasonReportDto dto = calc(s);
        assertThat(dto.nudgeShare()).isNull();
        assertThat(dto.nudgeSameTurnShare()).isNull();
        assertThat(dto.turnoverAnnual().toPlainString()).isEqualTo("0.00000000");
        assertThat(dto.totalFee().toPlainString()).isEqualTo("0");
    }

    @Test
    void 대금이_영이어도_영_턴_수수료는_총_수수료와_팀_수수료에_남는다() {
        // 0턴 매수 수수료만 있는 시즌(대금은 1턴 이후 체결만 센다): 비용은 보존되고 회전율은 영, 독려 비중은 없다
        OrderFacts onlyTeamFee = new OrderFacts(won(0), won(7_000), won(7_000), won(0), won(0), won(0), 0, 0);
        SeasonReportDto dto = calc(SeasonReportFixtures.withOrders(threeTurns(), onlyTeamFee));
        assertThat(dto.totalFee().toPlainString()).isEqualTo("7000");
        assertThat(dto.teamFee().toPlainString()).isEqualTo("7000");
        assertThat(dto.tradeNotional().toPlainString()).isEqualTo("0");
        assertThat(dto.turnoverAnnual().toPlainString()).isEqualTo("0.00000000");
        assertThat(dto.nudgeShare()).isNull();
        assertThat(dto.nudgeSameTurnShare()).isNull();
    }

    @Test
    void 표본이_부족하면_역할_수행은_널이고_판정_불능_선수만_센다() {
        // 3턴 시즌은 최소 6개월에 못 미쳐 모든 선수가 판정 불능 — 노출 선수 둘(A, B)이 모두 "못 쟀다"
        SeasonReportDto dto = calc(threeTurns());
        assertThat(dto.roleFit()).isNull();
        assertThat(dto.rolePlayersJudged()).isZero();
        assertThat(dto.rolePlayersUnjudged()).isEqualTo(2);
    }

    @Test
    void 한_턴짜리_시즌은_월_변동성이_널이다() {
        SeasonFacts season = new SeasonFacts(SeasonStatus.FINISHED, 1, 1, won(30_000_000), bd("-0.10000000"),
                bd("0.00100000"), bd("0.00200000"), bd("0.00400000"), 20, 30, 30, 20);
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        pool.put(KR, returns("0.01"));
        pool.put(US, returns("0.01"));
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        bench.put(KR, prices(30_000, 31_000));
        bench.put(US, prices(70_000, 72_000));
        SeasonReportSources s = new SeasonReportSources(season,
                List.of(turn(1, 30_300_000, "0.00000000", 6_060_000, 9_090_000, 9_090_000, 6_060_000)), List.of(),
                noOrders(), List.of(), List.of(), pool, bench, List.of(), new ReconFacts(0, 1, 0, 0));
        SeasonReportDto dto = calc(s);
        assertThat(dto.monthlyVolatility()).isNull();
        assertThat(dto.reportTurns()).isEqualTo(1);
        assertThat(dto.formationDeviation().toPlainString()).isEqualTo("0.00000000");
        assertThat(dto.maxDrawdown().toPlainString()).isEqualTo("0.00000000");
    }

    // ---------------------------------------------------------------- 역할 수행 (8턴 시즌, 판정 가능)

    /**
     * 8턴 시즌. 시장 대용 둘의 수익률이 같아 시장 = 그 값이다. A 는 시장의 0.1배(변동성 최저·하락 참여율 0.1 → 골키퍼),
     * B 는 0.5배(하락 참여율 0.5, 상·하위 변동성 아님 → 수비수), C 는 두 달치뿐이라 판정 불능이다.
     */
    private static SeasonReportSources eightTurns(Position declaredA, Position declaredB, List<HoldingFact> holdings) {
        SeasonFacts season = new SeasonFacts(SeasonStatus.FINISHED, 8, 8, won(30_000_000), bd("-0.50000000"),
                bd("0.00100000"), bd("0.00200000"), bd("0.00400000"), 100, 0, 0, 0);
        List<TurnFact> turns = new ArrayList<>();
        for (int t = 1; t <= 8; t++) {
            turns.add(turn(t, 30_000_000L + 100_000L * t, "0.00000000", 30_000_000L + 100_000L * t, 0, 0, 0));
        }
        String[] m = {"0.03", "0.02", "-0.02", "0.04", "-0.03", "0.01", "-0.01", "0.05"};
        String[] a = {"0.003", "0.002", "-0.002", "0.004", "-0.003", "0.001", "-0.001", "0.005"};
        String[] b = {"0.015", "0.01", "-0.01", "0.02", "-0.015", "0.005", "-0.005", "0.025"};
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        pool.put(KR, returns(m));
        pool.put(US, returns(m));
        pool.put(A, returns(a));
        pool.put(B, returns(b));
        pool.put(C_ASSET, returns(null, null, "0.01", "0.02", null, null, null, null));
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        bench.put(KR, prices(30_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000));
        bench.put(US, prices(70_000, 70_000, 70_000, 70_000, 70_000, 70_000, 70_000, 70_000, 70_000));
        List<LineupSpan> lineup = List.of(new LineupSpan(A, declaredA, 0, null), new LineupSpan(B, declaredB, 0, null),
                new LineupSpan(C_ASSET, Position.DEFENDER, 0, null));
        return new SeasonReportSources(season, turns, List.of(), noOrders(), holdings, lineup, pool, bench, List.of(),
                new ReconFacts(0, 8, 0, 0));
    }

    private static final AssetKey C_ASSET = new AssetKey("KR", "CCC003");

    private static List<HoldingFact> roleHoldings() {
        List<HoldingFact> h = new ArrayList<>();
        for (int t = 2; t <= 8; t++) {
            h.add(new HoldingFact(A, t, won(1_000_000)));
        }
        for (int t = 1; t <= 8; t++) {
            h.add(new HoldingFact(B, t, won(500_000)));
        }
        h.add(new HoldingFact(C_ASSET, 3, won(300_000)));
        return h;
    }

    @Test
    void 역할_수행은_일치한_선수의_노출_가치_비중이다() {
        // A: 선언 골키퍼 = 실측 골키퍼 (노출 7턴 × 1,000,000), B: 선언 미드필더 ≠ 실측 수비수 (8턴 × 500,000)
        // C: 판정 불능(표본 부족)이라 분자·분모에서 모두 빠진다 → 7,000,000 ÷ 11,000,000
        SeasonReportDto dto = calc(eightTurns(Position.GOALKEEPER, Position.MIDFIELDER, roleHoldings()));
        assertThat(dto.roleFit().toPlainString()).isEqualTo("0.63636364");
        assertThat(dto.rolePlayersJudged()).isEqualTo(2);
        assertThat(dto.rolePlayersUnjudged()).isEqualTo(1);
    }

    @Test
    void 선언이_모두_실측과_같으면_역할_수행은_일이다() {
        SeasonReportDto dto = calc(eightTurns(Position.GOALKEEPER, Position.DEFENDER, roleHoldings()));
        assertThat(dto.roleFit().toPlainString()).isEqualTo("1.00000000");
    }

    @Test
    void 선언이_모두_틀리면_역할_수행은_영이다() {
        SeasonReportDto dto = calc(eightTurns(Position.DEFENDER, Position.GOALKEEPER, roleHoldings()));
        assertThat(dto.roleFit().toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 판정_불능_선수만_들고_있으면_역할_수행은_널이다() {
        List<HoldingFact> onlyC = List.of(new HoldingFact(C_ASSET, 3, won(300_000)));
        SeasonReportDto dto = calc(eightTurns(Position.GOALKEEPER, Position.DEFENDER, onlyC));
        assertThat(dto.roleFit()).isNull();
        assertThat(dto.rolePlayersJudged()).isZero();
        assertThat(dto.rolePlayersUnjudged()).isEqualTo(1);
    }

    @Test
    void 같은_종목의_선언이_바뀌면_선언_구간마다_따로_센다() {
        // A 를 1–3턴(2·3턴 노출)은 수비수, 4턴부터는 골키퍼로 선언 — 일치하는 5턴(4–8턴)만 분자
        List<LineupSpan> lineup = List.of(new LineupSpan(A, Position.DEFENDER, 0, 3), new LineupSpan(A, Position.GOALKEEPER, 3, null),
                new LineupSpan(B, Position.DEFENDER, 0, null), new LineupSpan(C_ASSET, Position.DEFENDER, 0, null));
        SeasonReportSources base = eightTurns(Position.GOALKEEPER, Position.DEFENDER, roleHoldings());
        SeasonReportSources s = SeasonReportFixtures.withHoldings(base, base.holdings(), lineup);
        // 분자 A 5턴 5,000,000 + B 4,000,000 = 9,000,000, 분모 A 7,000,000 + B 4,000,000 = 11,000,000
        SeasonReportDto dto = calc(s);
        assertThat(dto.roleFit().toPlainString()).isEqualTo("0.81818182");
        // 종목 수는 선언 구간이 아니라 종목 단위로 센다: A 는 구간이 둘이어도 한 종목
        assertThat(dto.rolePlayersJudged()).isEqualTo(2);
        assertThat(dto.rolePlayersUnjudged()).isEqualTo(1);
    }

    @Test
    void 판정_불능_종목의_선언이_바뀌어도_한_종목으로_센다() {
        // C(표본 부족)를 1–3턴은 수비수, 4턴부터는 미드필더로 선언하고 3턴·5턴에 둘 다 보유 — 두 구간에 노출되어도 불능 종목은 하나
        List<LineupSpan> lineup = List.of(new LineupSpan(A, Position.GOALKEEPER, 0, null), new LineupSpan(B, Position.MIDFIELDER, 0, null),
                new LineupSpan(C_ASSET, Position.DEFENDER, 0, 3), new LineupSpan(C_ASSET, Position.MIDFIELDER, 3, null));
        SeasonReportSources base = eightTurns(Position.GOALKEEPER, Position.DEFENDER, roleHoldings());
        List<HoldingFact> holdings = new ArrayList<>(roleHoldings());
        holdings.add(new HoldingFact(C_ASSET, 5, won(300_000)));
        SeasonReportDto dto = calc(SeasonReportFixtures.withHoldings(base, holdings, lineup));
        assertThat(dto.rolePlayersJudged()).isEqualTo(2);
        assertThat(dto.rolePlayersUnjudged()).isEqualTo(1);
        // 불능 종목은 분자·분모에서 모두 빠진다 — 선언을 바꾸지 않았을 때와 같은 비율
        assertThat(dto.roleFit().toPlainString()).isEqualTo("0.63636364");
    }
}
