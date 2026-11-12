package antlab.scoring;

import antlab.asset.Position;
import antlab.season.FormationCost;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 성적표 계산기의 입력 묶음 — 정수·원 단위로 읽어 온 원천 값이다. 계산기는 스프링 없이 이 묶음만 읽는 순수 함수라, 시험이 손으로 만든다.
 * 원천 조회(읽기 전용 한 스냅샷)가 이 모양으로 채운다. 날짜·월 블록 번호·종목 이름은 없다 — 종목은 자연 키 {@link AssetKey} 로만 가리킨다.
 *
 * @param season       시즌 사실(상태·보고 턴·초기 자금·위험 선언·요율·시작 포메이션)
 * @param turns        턴 스냅샷 1..T, 턴 번호 순. 정본이며 다시 계산하지 않는다
 * @param changes      시즌의 전술 변경 전부, id 순(같은 턴의 마지막은 id 가 큰 쪽)
 * @param orders       주문·체결 집계(주문별로 먼저 합친 뒤 구한 값)
 * @param holdings     턴별 보유 재구성: 턴 t 시작 시점에 수량이 0보다 큰 (종목, 턴)의 평가액. 현재 보유로 과거를 만들지 않는다
 * @param lineup       라인업 구간 시즌 전부(길이 없는 구간 포함)
 * @param poolReturns  기준일 풀 종목마다 경로 1..T 의 원화 월 수익률(길이 T, 없는 달은 null)
 * @param benchmarkPrices 기준선 대용 두 종목의 0턴..T 가격(길이 T+1)
 * @param changeCosts  전술 변경별 비용(주문 없는 변경 포함), 변경 id 순
 * @param recon        대사 집계(건수뿐)
 */
public record SeasonReportSources(
        SeasonFacts season,
        List<TurnFact> turns,
        List<ChangeFact> changes,
        OrderFacts orders,
        List<HoldingFact> holdings,
        List<LineupSpan> lineup,
        Map<AssetKey, List<BigDecimal>> poolReturns,
        Map<AssetKey, List<BigDecimal>> benchmarkPrices,
        List<FormationCost> changeCosts,
        ReconFacts recon) {

    /** 종목의 자연 키 — (시장, 티커). */
    public record AssetKey(String market, String ticker) {
    }

    /** 시즌 사실. {@code currentTurnNo} 가 보고 턴 T 다. 요율은 소수 8자리. */
    public record SeasonFacts(SeasonStatus status, int pathLength, int currentTurnNo, BigDecimal initialCash,
            BigDecimal riskLimit, BigDecimal feeRateKr, BigDecimal feeRateUs, BigDecimal fxSpreadRate,
            int startGoalkeeper, int startDefender, int startMidfielder, int startForward) {
    }

    /** 턴 스냅샷 한 줄(저장된 정본 값). */
    public record TurnFact(int turnNo, BigDecimal totalValue, BigDecimal drawdown, BigDecimal goalkeeperValue,
            BigDecimal defenderValue, BigDecimal midfielderValue, BigDecimal forwardValue) {
    }

    /** 전술 변경 한 건: 변경 뒤 목표 포메이션. */
    public record ChangeFact(long id, int turnNo, int afterGoalkeeper, int afterDefender, int afterMidfielder,
            int afterForward) {
    }

    /**
     * 주문·체결 집계. 모두 turn_no &lt; T 의 주문만 센 원 단위 정수 합이다. 매매 대금과 독려 대금은 1턴 이후(0턴 팀 구성 제외),
     * 수수료는 0턴 포함이다. {@code pendingOrders} 는 turn_no = T 의 주문 수, {@code ordersAfterReport} 는 turn_no &gt; T 의 주문 수다.
     */
    public record OrderFacts(BigDecimal tradeNotional, BigDecimal totalFee, BigDecimal teamFee,
            BigDecimal platformNudgeFee, BigDecimal nudgeNotional, BigDecimal nudgeSameTurnNotional, int pendingOrders,
            int ordersAfterReport) {
    }

    /** 턴 t 시작 시점의 보유 한 종목: 평가액 = 수량 × 그 턴의 turn_price. */
    public record HoldingFact(AssetKey asset, int turnNo, BigDecimal value) {
    }

    /** 라인업 구간: 노출 턴 k 의 선언은 startTurn &lt; k ≤ endTurn(null 은 열린 끝). */
    public record LineupSpan(AssetKey asset, Position declared, int startTurn, Integer endTurn) {
    }

    /** 대사 집계: MISMATCH 행 수, 17행 묶음 수, 15행 묶음 수, 경로 길이를 넘는 턴의 행 수. */
    public record ReconFacts(int mismatchRows, int turnBundles, int manualBundles, int outOfRangeRows) {
    }
}
