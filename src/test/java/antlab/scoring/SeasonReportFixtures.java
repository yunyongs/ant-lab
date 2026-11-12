package antlab.scoring;

import antlab.asset.Position;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.scoring.SeasonReportSources.HoldingFact;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.OrderFacts;
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

/** 성적표 계산기 시험용 손 원천. 값은 모두 합성이다(시세 아님). 시험이 필요한 칸만 바꿔 쓰도록 복사 도우미를 둔다. */
final class SeasonReportFixtures {

    static final AssetKey KR = new AssetKey("KR", "069500");
    static final AssetKey US = new AssetKey("US", "SPY");
    static final AssetKey A = new AssetKey("KR", "AAA001");
    static final AssetKey B = new AssetKey("US", "BBB002");

    private SeasonReportFixtures() {
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    static BigDecimal won(long v) {
        return new BigDecimal(v).setScale(4);
    }

    static TurnFact turn(int no, long total, String drawdown, long gk, long df, long mf, long fw) {
        return new TurnFact(no, won(total), bd(drawdown), won(gk), won(df), won(mf), won(fw));
    }

    static OrderFacts noOrders() {
        return new OrderFacts(won(0), won(0), won(0), won(0), won(0), won(0), 0, 0);
    }

    /**
     * 3턴 끝난 시즌. 초기 자금 3천만 원, 위험 선언 −0.01, 요율 KR 0.1% · US 0.2% + 환전 0.4%, 시작 포메이션 20/30/30/20.
     * 전술 변경 셋(1턴에 둘, 2턴에 하나), 보유 둘, 라인업(영 길이 구간 포함), 대용 두 종목의 가격과 수익률을 갖는다.
     */
    static SeasonReportSources threeTurns() {
        SeasonFacts season = new SeasonFacts(SeasonStatus.FINISHED, 3, 3, won(30_000_000), bd("-0.01000000"),
                bd("0.00100000"), bd("0.00200000"), bd("0.00400000"), 20, 30, 30, 20);
        List<TurnFact> turns = List.of(
                turn(1, 30_300_000, "0.00000000", 6_060_000, 9_090_000, 9_090_000, 6_060_000),
                turn(2, 29_700_000, "-0.01980198", 5_000_000, 9_000_000, 9_700_000, 6_000_000),
                turn(3, 30_900_002, "0.00000000", 7_000_000, 8_000_000, 7_900_002, 8_000_000));
        List<ChangeFact> changes = List.of(
                new ChangeFact(11, 1, 10, 40, 30, 20),
                new ChangeFact(12, 1, 25, 25, 25, 25),
                new ChangeFact(13, 2, 40, 20, 20, 20));
        OrderFacts orders = new OrderFacts(won(12_000_000), won(60_000), won(40_000), won(5_000), won(3_000_000),
                won(1_200_000), 0, 0);
        List<HoldingFact> holdings = List.of(
                new HoldingFact(A, 1, won(3_000_000)), new HoldingFact(B, 1, won(2_000_000)),
                new HoldingFact(A, 2, won(3_300_000)), new HoldingFact(B, 2, won(1_900_000)),
                new HoldingFact(A, 3, won(4_000_000)));
        List<LineupSpan> lineup = List.of(
                new LineupSpan(A, Position.FORWARD_TARGET, 0, 1),
                new LineupSpan(A, Position.MIDFIELDER, 1, 1), // 길이 없는 구간 — 어떤 턴과도 맞지 않는다
                new LineupSpan(A, Position.GOALKEEPER, 1, null),
                new LineupSpan(B, Position.DEFENDER, 0, 2));
        Map<AssetKey, List<BigDecimal>> pool = new LinkedHashMap<>();
        pool.put(KR, returns("0.01", "-0.02", "0.03"));
        pool.put(US, returns("0.01", "-0.02", "0.03"));
        Map<AssetKey, List<BigDecimal>> bench = new LinkedHashMap<>();
        bench.put(KR, prices(30_000, 31_000, 29_000, 33_001));
        bench.put(US, prices(70_000, 72_000, 68_000, 77_000));
        List<FormationCost> costs = List.of(
                new FormationCost(11, 1, 0, won(0), won(0), won(0), won(0)),
                new FormationCost(12, 1, 2, won(1_000_000), won(500_000), won(1_200), won(0)),
                new FormationCost(13, 2, 1, won(300_000), won(0), won(500), won(0)));
        return new SeasonReportSources(season, turns, changes, orders, holdings, lineup, pool, bench, costs,
                new ReconFacts(2, 3, 1, 0));
    }

    static List<BigDecimal> returns(String... v) {
        List<BigDecimal> out = new ArrayList<>();
        for (String s : v) {
            out.add(s == null ? null : bd(s));
        }
        return out;
    }

    static List<BigDecimal> prices(long... v) {
        List<BigDecimal> out = new ArrayList<>();
        for (long p : v) {
            out.add(won(p));
        }
        return out;
    }

    // ---- 복사 도우미: 필요한 칸만 바꾼다

    static SeasonReportSources withSeason(SeasonReportSources s, SeasonFacts season) {
        return new SeasonReportSources(season, s.turns(), s.changes(), s.orders(), s.holdings(), s.lineup(),
                s.poolReturns(), s.benchmarkPrices(), s.changeCosts(), s.recon());
    }

    static SeasonReportSources withTurns(SeasonReportSources s, List<TurnFact> turns) {
        return new SeasonReportSources(s.season(), turns, s.changes(), s.orders(), s.holdings(), s.lineup(),
                s.poolReturns(), s.benchmarkPrices(), s.changeCosts(), s.recon());
    }

    static SeasonReportSources withOrders(SeasonReportSources s, OrderFacts orders) {
        return new SeasonReportSources(s.season(), s.turns(), s.changes(), orders, s.holdings(), s.lineup(),
                s.poolReturns(), s.benchmarkPrices(), s.changeCosts(), s.recon());
    }

    static SeasonReportSources withChanges(SeasonReportSources s, List<ChangeFact> changes, List<FormationCost> costs) {
        return new SeasonReportSources(s.season(), s.turns(), changes, s.orders(), s.holdings(), s.lineup(),
                s.poolReturns(), s.benchmarkPrices(), costs, s.recon());
    }

    static SeasonReportSources withHoldings(SeasonReportSources s, List<HoldingFact> holdings, List<LineupSpan> lineup) {
        return new SeasonReportSources(s.season(), s.turns(), s.changes(), s.orders(), holdings, lineup,
                s.poolReturns(), s.benchmarkPrices(), s.changeCosts(), s.recon());
    }

    static SeasonReportSources withMarket(SeasonReportSources s, Map<AssetKey, List<BigDecimal>> pool,
            Map<AssetKey, List<BigDecimal>> bench) {
        return new SeasonReportSources(s.season(), s.turns(), s.changes(), s.orders(), s.holdings(), s.lineup(),
                pool, bench, s.changeCosts(), s.recon());
    }

    static SeasonFacts seasonWith(SeasonFacts f, SeasonStatus status, int pathLength, int current, String riskLimit) {
        return new SeasonFacts(status, pathLength, current, f.initialCash(), bd(riskLimit), f.feeRateKr(),
                f.feeRateUs(), f.fxSpreadRate(), f.startGoalkeeper(), f.startDefender(), f.startMidfielder(),
                f.startForward());
    }
}
