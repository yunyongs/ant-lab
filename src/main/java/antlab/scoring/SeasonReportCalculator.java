package antlab.scoring;

import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.OrderFacts;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import antlab.season.FormationCost;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 성적표 계산기 — 정수·원 단위 원천({@link SeasonReportSources})에서 37값({@link SeasonReportDto})을 만드는 순수 함수다.
 * SQL 정의 {@code db/26_season_report_def.sql} 의 {@code sr_report} 와 같은 값을 내도록 단계를 그대로 따른다: 금액은 정수 합·곱이라 반올림이 없고,
 * 비율은 {@link MoneyMath#ratio} 한 곳에서 8자리 한 번, 평균·변동성은 {@code MoneyMath} 의 짝 메서드가 SQL 의 중간 단계까지 따른다(계획 W2).
 * 원천이 서로 모순이면 {@link IllegalStateException} 이다 — 메시지는 고정 문장이고 금액·날짜·번호를 담지 않는다.
 */
public final class SeasonReportCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final AssetKey BENCH_KR = new AssetKey(ReportRules.BENCH_KR_MARKET, ReportRules.BENCH_KR_TICKER);
    private static final AssetKey BENCH_US = new AssetKey(ReportRules.BENCH_US_MARKET, ReportRules.BENCH_US_TICKER);

    private SeasonReportCalculator() {
    }

    public static SeasonReportDto calculate(SeasonReportSources src) {
        SeasonFacts season = src.season();
        int t = season.currentTurnNo();
        validate(src);

        List<TurnFact> turns = src.turns();
        BigDecimal initialCash = season.initialCash();
        BigDecimal finalValue = turns.get(t - 1).totalValue();
        BigDecimal sumValue = BigDecimal.ZERO;
        for (TurnFact turn : turns) {
            sumValue = sumValue.add(turn.totalValue());
        }

        // 결과·위험: 턴 스냅샷의 정본 값
        BigDecimal totalReturn = MoneyMath.ratio(finalValue.subtract(initialCash), initialCash);
        List<BigDecimal> periodReturns = new ArrayList<>();
        BigDecimal previous = initialCash;
        for (TurnFact turn : turns) {
            periodReturns.add(MoneyMath.pad40Divide(turn.totalValue().subtract(previous), previous));
            previous = turn.totalValue();
        }
        BigDecimal monthlyVolatility = MoneyMath.sampleStddev8(periodReturns);
        BigDecimal maxDrawdown = null;
        int riskBreachTurns = 0;
        BigDecimal minDiff = null;
        for (TurnFact turn : turns) {
            maxDrawdown = maxDrawdown == null || turn.drawdown().compareTo(maxDrawdown) < 0 ? turn.drawdown() : maxDrawdown;
            BigDecimal diff = turn.drawdown().subtract(season.riskLimit());
            minDiff = minDiff == null || diff.compareTo(minDiff) < 0 ? diff : minDiff;
            if (turn.drawdown().compareTo(season.riskLimit()) < 0) {
                riskBreachTurns++;
            }
        }
        BigDecimal riskBreachDepth = MoneyMath.round8(minDiff.min(BigDecimal.ZERO));

        // 기준선과 초과 수익률 (초과는 한 번만 나눈다 — 두 수익률의 차가 아니다)
        BigDecimal baselineFinal = baselineValue(src, t);
        BigDecimal baselineReturn = MoneyMath.ratio(baselineFinal.subtract(initialCash), initialCash);
        BigDecimal excessReturn = MoneyMath.ratio(finalValue.subtract(baselineFinal), initialCash);

        // 턴별 보유 최대 가치 → 최대 단일 비중과 포메이션 준수의 분모
        Map<Integer, BigDecimal> maxHolding = new HashMap<>();
        for (HoldingFact holding : src.holdings()) {
            maxHolding.merge(holding.turnNo(), holding.value(), BigDecimal::max);
        }
        BigDecimal maxSingleWeight = null;
        List<BigInteger> deviationNumerators = new ArrayList<>();
        List<BigInteger> deviationDenominators = new ArrayList<>();
        for (TurnFact turn : turns) {
            BigDecimal weight = MoneyMath.ratio(maxHolding.getOrDefault(turn.turnNo(), BigDecimal.ZERO), turn.totalValue());
            maxSingleWeight = maxSingleWeight == null || weight.compareTo(maxSingleWeight) > 0 ? weight : maxSingleWeight;
            int[] target = targetFormation(src, turn.turnNo());
            BigDecimal total = turn.totalValue();
            BigDecimal numerator = deviation(turn.goalkeeperValue(), target[0], total)
                    .add(deviation(turn.defenderValue(), target[1], total))
                    .add(deviation(turn.midfielderValue(), target[2], total))
                    .add(deviation(turn.forwardValue(), target[3], total));
            deviationNumerators.add(exactInteger(numerator));
            deviationDenominators.add(exactInteger(HUNDRED.multiply(total)));
        }
        BigDecimal formationDeviation = MoneyMath.fractionMean(deviationNumerators, deviationDenominators);

        // 매매·비용
        OrderFacts orders = src.orders();
        BigDecimal turnoverAnnual = MoneyMath.ratio(
                BigDecimal.valueOf(ReportRules.ANNUAL_TURNS).multiply(orders.tradeNotional()), sumValue);
        BigDecimal nudgeShare = null;
        BigDecimal nudgeSameTurnShare = null;
        if (orders.tradeNotional().signum() > 0) {
            nudgeShare = MoneyMath.exactRate(MoneyMath.ratio(orders.nudgeNotional(), orders.tradeNotional()));
            nudgeSameTurnShare = MoneyMath.exactRate(
                    MoneyMath.ratio(orders.nudgeSameTurnNotional(), orders.tradeNotional()));
        }
        BigDecimal formationChangeFee = BigDecimal.ZERO;
        int formationChanges = 0;
        long formationChangeOrders = 0;
        int changesWithoutOrders = 0;
        for (FormationCost cost : src.changeCosts()) {
            if (cost.turnNo() >= t) {
                continue;
            }
            formationChanges++;
            formationChangeFee = formationChangeFee.add(cost.feeSum());
            formationChangeOrders += cost.orderCount();
            if (cost.orderCount() == 0) {
                changesWithoutOrders++;
            }
        }

        // 역할 수행
        Role role = role(src);

        int pendingChanges = (int) src.changes().stream().filter(c -> c.turnNo() == t).count();
        SeasonReportSources.ReconFacts recon = src.recon();
        return new SeasonReportDto(
                season.status(), season.status() == SeasonStatus.FINISHED, t, season.pathLength(),
                orders.pendingOrders(), pendingChanges,
                MoneyMath.exactWon(initialCash), MoneyMath.exactWon(finalValue), MoneyMath.exactRate(totalReturn),
                monthlyVolatility == null ? null : MoneyMath.exactRate(monthlyVolatility),
                MoneyMath.exactRate(maxDrawdown),
                MoneyMath.exactWon(baselineFinal), MoneyMath.exactRate(baselineReturn), MoneyMath.exactRate(excessReturn),
                MoneyMath.exactRate(season.riskLimit()), riskBreachTurns, MoneyMath.exactRate(riskBreachDepth),
                MoneyMath.exactRate(maxSingleWeight),
                MoneyMath.exactWon(orders.tradeNotional()), MoneyMath.exactRate(turnoverAnnual),
                MoneyMath.exactRate(formationDeviation),
                role.fit(), role.judged(), role.unjudged(),
                nudgeShare, nudgeSameTurnShare,
                MoneyMath.exactWon(orders.totalFee()), MoneyMath.exactWon(orders.teamFee()),
                MoneyMath.exactWon(formationChangeFee), MoneyMath.exactWon(orders.platformNudgeFee()),
                formationChanges, (int) formationChangeOrders, changesWithoutOrders,
                recon.mismatchRows(), recon.turnBundles(), recon.manualBundles(), recon.outOfRangeRows());
    }

    // ------------------------------------------------------------------ 기준선

    /**
     * 시즌 기준일의 대용 두 종목을 0턴 가격으로 사서 끝까지 보유한 평가액(SQL {@code sr_baseline}): 예산 = 초기 자금 × 비중,
     * 정수 주 = {@code teamQty}, 남은 현금 = 초기 자금 − Σ(수량 × 0턴 가격 + 수수료), B_t = Σ 수량 × t턴 가격 + 남은 현금.
     */
    /** 기준선의 한 다리: 대용 종목과 초기 자금 중 매수 비중. */
    private record BenchmarkLeg(AssetKey key, BigDecimal weight) {
    }

    private static final List<BenchmarkLeg> BENCHMARK_LEGS = List.of(
            new BenchmarkLeg(BENCH_KR, ReportRules.BENCH_KR_WEIGHT), new BenchmarkLeg(BENCH_US, ReportRules.BENCH_US_WEIGHT));

    private static BigDecimal baselineValue(SeasonReportSources src, int turnNo) {
        SeasonFacts season = src.season();
        BigDecimal cash = season.initialCash();
        BigDecimal held = BigDecimal.ZERO;
        for (BenchmarkLeg leg : BENCHMARK_LEGS) {
            AssetKey key = leg.key();
            BigDecimal weight = leg.weight();
            List<BigDecimal> prices = src.benchmarkPrices().get(key);
            BigDecimal rate = MoneyMath.feeRate(key.market(), season.feeRateKr(), season.feeRateUs(), season.fxSpreadRate());
            BigDecimal firstPrice = prices.get(0);
            int quantity = MoneyMath.teamQty(season.initialCash().multiply(weight), firstPrice, rate);
            BigDecimal fee = quantity == 0 ? BigDecimal.ZERO : MoneyMath.fee(firstPrice, quantity, rate);
            BigDecimal count = BigDecimal.valueOf(quantity);
            cash = cash.subtract(count.multiply(firstPrice).add(fee));
            held = held.add(count.multiply(prices.get(turnNo)));
        }
        return held.add(cash);
    }

    // ------------------------------------------------------------------ 포메이션 준수

    /** 턴 t 시작 시점의 목표: turn_no &lt; t 인 마지막(id 순) 전술 변경의 변경 뒤 값, 없으면 시작 포메이션. */
    private static int[] targetFormation(SeasonReportSources src, int turnNo) {
        ChangeFact last = null;
        for (ChangeFact change : src.changes()) {
            if (change.turnNo() < turnNo) {
                last = change;
            }
        }
        SeasonFacts s = src.season();
        return last == null
                ? new int[] {s.startGoalkeeper(), s.startDefender(), s.startMidfielder(), s.startForward()}
                : new int[] {last.afterGoalkeeper(), last.afterDefender(), last.afterMidfielder(), last.afterForward()};
    }

    /** |100·v − f·V| — 포지션 하나의 이탈(분자). */
    private static BigDecimal deviation(BigDecimal positionValue, int target, BigDecimal total) {
        return HUNDRED.multiply(positionValue).subtract(BigDecimal.valueOf(target).multiply(total)).abs();
    }

    private static BigInteger exactInteger(BigDecimal value) {
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException ex) {
            throw inconsistent("원 단위 정수가 아닌 값이 있다");
        }
    }

    // ------------------------------------------------------------------ 역할 수행

    private record Role(BigDecimal fit, int judged, int unjudged) {
    }

    /** 선수별 노출(보유한 턴 × 그 턴의 선언)과 시즌 실측 포지션을 맞춰 노출 가치 가중으로 일치도를 센다(SQL {@code sr_players}). */
    private static Role role(SeasonReportSources src) {
        int t = src.season().currentTurnNo();
        // 시즌 실측: 기준일 풀 전체를 경로 1..T 의 원화 수익률로 잰다(상대 순위에 풀이 필요하다)
        List<BigDecimal> market = new ArrayList<>();
        List<BigDecimal> krReturns = src.poolReturns().get(BENCH_KR);
        List<BigDecimal> usReturns = src.poolReturns().get(BENCH_US);
        for (int i = 0; i < t; i++) {
            market.add(PositionMeter.marketReturn(krReturns.get(i), usReturns.get(i)));
        }
        Map<AssetKey, PositionMeter.Metrics> metrics = new LinkedHashMap<>();
        for (Map.Entry<AssetKey, List<BigDecimal>> pool : src.poolReturns().entrySet()) {
            metrics.put(pool.getKey(), PositionMeter.measure(pool.getValue(), market, ReportRules.SEASON_MIN_MONTHS,
                    ReportRules.SEASON_MIN_UP, ReportRules.SEASON_MIN_DOWN));
        }
        Map<AssetKey, Position> measured = PositionMeter.classifyPool(metrics, PositionMeter.maxDrawdown(market));

        // 노출: (종목, 선언)마다 노출 가치 합
        record Player(AssetKey asset, Position declared) {
        }
        Map<Player, BigDecimal> exposure = new LinkedHashMap<>();
        for (HoldingFact holding : src.holdings()) {
            Position declared = declaredAt(src.lineup(), holding.asset(), holding.turnNo());
            exposure.merge(new Player(holding.asset(), declared), holding.value(), BigDecimal::add);
        }
        BigDecimal matched = BigDecimal.ZERO;
        BigDecimal judgedValue = BigDecimal.ZERO;
        Set<AssetKey> judgedAssets = new HashSet<>();
        Set<AssetKey> unjudgedAssets = new HashSet<>();
        for (Map.Entry<Player, BigDecimal> entry : exposure.entrySet()) {
            Position actual = measured.get(entry.getKey().asset());
            if (actual == null) {
                unjudgedAssets.add(entry.getKey().asset());
                continue;
            }
            judgedAssets.add(entry.getKey().asset());
            judgedValue = judgedValue.add(entry.getValue());
            if (actual == entry.getKey().declared()) {
                matched = matched.add(entry.getValue());
            }
        }
        BigDecimal fit = judgedValue.signum() > 0 ? MoneyMath.ratio(matched, judgedValue) : null;
        return new Role(fit == null ? null : MoneyMath.exactRate(fit), judgedAssets.size(), unjudgedAssets.size());
    }

    private static Position declaredAt(List<LineupSpan> lineup, AssetKey asset, int turnNo) {
        Position found = null;
        int count = 0;
        for (LineupSpan span : lineup) {
            if (span.asset().equals(asset) && span.startTurn() < turnNo
                    && (span.endTurn() == null || turnNo <= span.endTurn())) {
                found = span.declared();
                count++;
            }
        }
        if (count != 1) {
            throw inconsistent("보유한 턴에 열린 선언 구간이 하나가 아니다");
        }
        return found;
    }

    // ------------------------------------------------------------------ 정합 검사 (SQL sr_t 의 짝)

    private static void validate(SeasonReportSources src) {
        SeasonFacts season = src.season();
        int t = season.currentTurnNo();
        if (t < 1 || t > season.pathLength()) {
            throw inconsistent("보고 턴이 범위를 벗어났다");
        }
        if (src.turns().size() != t) {
            throw inconsistent("턴 스냅샷이 처음부터 보고 턴까지 이어지지 않는다");
        }
        for (int i = 0; i < t; i++) {
            if (src.turns().get(i).turnNo() != i + 1) {
                throw inconsistent("턴 스냅샷이 처음부터 보고 턴까지 이어지지 않는다");
            }
        }
        if (src.orders().ordersAfterReport() != 0) {
            throw inconsistent("보고 턴 뒤의 주문이 있다");
        }
        if (season.status() == SeasonStatus.FINISHED) {
            long pendingChanges = src.changes().stream().filter(c -> c.turnNo() == t).count();
            if (t != season.pathLength() || src.orders().pendingOrders() != 0 || pendingChanges != 0) {
                throw inconsistent("끝난 시즌에 마지막 턴의 주문이나 전술 변경이 남아 있다");
            }
        }
        for (AssetKey key : List.of(BENCH_KR, BENCH_US)) {
            List<BigDecimal> prices = src.benchmarkPrices().get(key);
            if (prices == null || prices.size() != t + 1 || prices.stream().anyMatch(p -> p == null)) {
                throw inconsistent("기준선 대용 종목이 시즌 풀 또는 가격에 없다");
            }
            List<BigDecimal> returns = src.poolReturns().get(key);
            if (returns == null) {
                throw inconsistent("시장 대용 종목이 시즌 풀에 없다");
            }
        }
        for (List<BigDecimal> returns : src.poolReturns().values()) {
            if (returns.size() != t) {
                throw inconsistent("경로 수익률이 처음부터 보고 턴까지 이어지지 않는다");
            }
        }
        for (HoldingFact holding : src.holdings()) {
            if (holding.turnNo() < 1 || holding.turnNo() > t) {
                throw inconsistent("보유 재구성이 보고 범위를 벗어났다");
            }
        }
    }

    private static IllegalStateException inconsistent(String reason) {
        return new IllegalStateException("성적표 원천이 모순이다: " + reason);
    }
}
