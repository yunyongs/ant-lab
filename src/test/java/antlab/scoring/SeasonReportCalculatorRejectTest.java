package antlab.scoring;

import static antlab.scoring.SeasonReportFixtures.A;
import static antlab.scoring.SeasonReportFixtures.KR;
import static antlab.scoring.SeasonReportFixtures.US;
import static antlab.scoring.SeasonReportFixtures.prices;
import static antlab.scoring.SeasonReportFixtures.returns;
import static antlab.scoring.SeasonReportFixtures.threeTurns;
import static antlab.scoring.SeasonReportFixtures.turn;
import static antlab.scoring.SeasonReportFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.OrderFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 성적표 계산기의 정합 검사 — 원천이 서로 모순이면 고정 문장으로 멈추고, 문장에는 금액·날짜·번호가 없다(SQL sr_t 의 짝). */
class SeasonReportCalculatorRejectTest {

    /** 계산기 예외 문장은 고정 문장이라 숫자가 하나도 없다 — 턴 번호·금액·달·id 가 한 자리라도 붙으면 값이 샌 것이다. */
    private static final Pattern LEAK = Pattern.compile("\\d");

    private static void rejected(SeasonReportSources sources) {
        assertThatThrownBy(() -> SeasonReportCalculator.calculate(sources))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(LEAK.matcher(e.getMessage()).find()).as("메시지에 값이 있다").isFalse());
    }

    @Test
    void 정상_원천은_멈추지_않는다() {
        SeasonReportCalculator.calculate(threeTurns());
    }

    @Test
    void 보고_턴이_범위를_벗어나면_멈춘다() {
        SeasonReportSources base = threeTurns();
        rejected(SeasonReportFixtures.withSeason(base,
                SeasonReportFixtures.seasonWith(base.season(), SeasonStatus.IN_PROGRESS, 3, 4, "-0.01000000")));
        rejected(SeasonReportFixtures.withSeason(base,
                SeasonReportFixtures.seasonWith(base.season(), SeasonStatus.IN_PROGRESS, 3, 0, "-0.01000000")));
    }

    @Test
    void 턴_스냅샷이_처음부터_이어지지_않으면_멈춘다() {
        SeasonReportSources base = threeTurns();
        // 한 줄 모자람
        rejected(SeasonReportFixtures.withTurns(base, base.turns().subList(0, 2)));
        // 번호가 건너뜀(1, 2, 4)
        List<TurnFact> skipped = new ArrayList<>(base.turns());
        skipped.set(2, turn(4, 30_900_002, "0.00000000", 7_000_000, 8_000_000, 7_900_002, 8_000_000));
        rejected(SeasonReportFixtures.withTurns(base, skipped));
        // 번호가 1이 아닌 곳에서 시작(2, 3, 4)
        List<TurnFact> shifted = new ArrayList<>();
        for (TurnFact t : base.turns()) {
            shifted.add(new TurnFact(t.turnNo() + 1, t.totalValue(), t.drawdown(), t.goalkeeperValue(), t.defenderValue(),
                    t.midfielderValue(), t.forwardValue()));
        }
        rejected(SeasonReportFixtures.withTurns(base, shifted));
    }

    @Test
    void 보고_턴_뒤의_주문이_있으면_멈춘다() {
        SeasonReportSources base = threeTurns();
        OrderFacts o = base.orders();
        rejected(SeasonReportFixtures.withOrders(base, new OrderFacts(o.tradeNotional(), o.totalFee(), o.teamFee(),
                o.platformNudgeFee(), o.nudgeNotional(), o.nudgeSameTurnNotional(), 0, 1)));
    }

    @Test
    void 끝난_시즌에_마지막_턴의_주문이나_변경이_남아_있으면_멈춘다() {
        SeasonReportSources base = threeTurns();
        OrderFacts o = base.orders();
        rejected(SeasonReportFixtures.withOrders(base, new OrderFacts(o.tradeNotional(), o.totalFee(), o.teamFee(),
                o.platformNudgeFee(), o.nudgeNotional(), o.nudgeSameTurnNotional(), 1, 0)));
        List<ChangeFact> changes = new ArrayList<>(base.changes());
        changes.add(new ChangeFact(14, 3, 20, 30, 30, 20));
        rejected(SeasonReportFixtures.withChanges(base, changes, base.changeCosts()));
    }

    @Test
    void 끝난_시즌인데_보고_턴이_경로_길이와_다르면_멈춘다() {
        SeasonReportSources base = threeTurns();
        rejected(SeasonReportFixtures.withSeason(base,
                SeasonReportFixtures.seasonWith(base.season(), SeasonStatus.FINISHED, 4, 3, "-0.01000000")));
    }

    @Test
    void 기준선_대용_가격이_모자라거나_비면_멈춘다() {
        SeasonReportSources base = threeTurns();
        // 가격 줄 수가 T + 1 이 아님
        Map<AssetKey, List<BigDecimal>> shortBench = new LinkedHashMap<>();
        shortBench.put(KR, prices(30_000, 31_000, 29_000));
        shortBench.put(US, base.benchmarkPrices().get(US));
        rejected(SeasonReportFixtures.withMarket(base, base.poolReturns(), shortBench));
        // 가격 칸이 비어 있음
        Map<AssetKey, List<BigDecimal>> holeBench = new LinkedHashMap<>();
        List<BigDecimal> hole = new ArrayList<>(base.benchmarkPrices().get(KR));
        hole.set(2, null);
        holeBench.put(KR, hole);
        holeBench.put(US, base.benchmarkPrices().get(US));
        rejected(SeasonReportFixtures.withMarket(base, base.poolReturns(), holeBench));
        // 대용 한 종목이 통째로 없음
        Map<AssetKey, List<BigDecimal>> missing = new LinkedHashMap<>();
        missing.put(KR, base.benchmarkPrices().get(KR));
        rejected(SeasonReportFixtures.withMarket(base, base.poolReturns(), missing));
    }

    @Test
    void 시장_대용이_시즌_풀에_없거나_경로_수익률이_이어지지_않으면_멈춘다() {
        SeasonReportSources base = threeTurns();
        Map<AssetKey, List<BigDecimal>> noUs = new LinkedHashMap<>();
        noUs.put(KR, returns("0.01", "-0.02", "0.03"));
        rejected(SeasonReportFixtures.withMarket(base, noUs, base.benchmarkPrices()));
        Map<AssetKey, List<BigDecimal>> shortPool = new LinkedHashMap<>(base.poolReturns());
        shortPool.put(KR, returns("0.01", "-0.02"));
        rejected(SeasonReportFixtures.withMarket(base, shortPool, base.benchmarkPrices()));
    }

    @Test
    void 시장_대용의_한_달_수익률이_비면_멈춘다() {
        SeasonReportSources base = threeTurns();
        Map<AssetKey, List<BigDecimal>> hole = new LinkedHashMap<>(base.poolReturns());
        hole.put(US, returns("0.01", null, "0.03"));
        rejected(SeasonReportFixtures.withMarket(base, hole, base.benchmarkPrices()));
    }

    @Test
    void 보유_재구성이_보고_범위를_벗어나면_멈춘다() {
        SeasonReportSources base = threeTurns();
        List<HoldingFact> holdings = new ArrayList<>(base.holdings());
        holdings.add(new HoldingFact(A, 4, won(1_000_000)));
        rejected(SeasonReportFixtures.withHoldings(base, holdings, base.lineup()));
        List<HoldingFact> zero = new ArrayList<>(base.holdings());
        zero.add(new HoldingFact(A, 0, won(1_000_000)));
        rejected(SeasonReportFixtures.withHoldings(base, zero, base.lineup()));
    }

    @Test
    void 보유한_턴에_열린_선언_구간이_하나가_아니면_멈춘다() {
        SeasonReportSources base = threeTurns();
        // 구간 없음: A 의 구간을 모두 지우면 1턴 보유가 선언을 찾지 못한다
        List<LineupSpan> none = new ArrayList<>();
        for (LineupSpan span : base.lineup()) {
            if (!span.asset().equals(A)) {
                none.add(span);
            }
        }
        rejected(SeasonReportFixtures.withHoldings(base, base.holdings(), none));
        // 구간 겹침: 같은 턴에 둘이 열려 있음
        List<LineupSpan> overlap = new ArrayList<>(base.lineup());
        overlap.add(new LineupSpan(A, Position.DEFENDER, 0, null));
        rejected(SeasonReportFixtures.withHoldings(base, base.holdings(), overlap));
    }

    @Test
    void 원_단위_정수가_아닌_턴_값이_있으면_멈춘다() {
        SeasonReportSources base = threeTurns();
        List<TurnFact> turns = new ArrayList<>(base.turns());
        TurnFact first = turns.get(0);
        turns.set(0, new TurnFact(1, first.totalValue(), first.drawdown(), first.goalkeeperValue().add(new BigDecimal("0.005")),
                first.defenderValue(), first.midfielderValue(), first.forwardValue()));
        rejected(SeasonReportFixtures.withTurns(base, turns));
    }

    @Test
    void 모순_메시지는_고정_문장이고_접두어가_같다() {
        SeasonReportSources base = threeTurns();
        assertThatThrownBy(() -> SeasonReportCalculator.calculate(SeasonReportFixtures.withTurns(base, base.turns().subList(0, 2))))
                .hasMessageStartingWith("성적표 원천이 모순이다");
        // 대용 이름·시장 코드도 메시지에 없다
        assertThatThrownBy(() -> SeasonReportCalculator.calculate(SeasonReportFixtures.withMarket(base,
                new LinkedHashMap<>(Map.of(KR, returns("0.01", "-0.02", "0.03"))), base.benchmarkPrices())))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SPY").doesNotContain("069500"));
        assertThat(US.ticker()).isEqualTo("SPY");
    }
}
