package antlab.season;

import antlab.account.CashAccount;
import antlab.account.Holding;
import antlab.account.HoldingRepository;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 턴 스냅샷 — {@code db/23} 의 {@code ta_snapshot} 의 Java 짝(erd 6.3 turn, D23·D26·D37).
 * 보유 평가액 = Σ 수량 × 그 턴 가격(가격 없는 보유는 조용히 빼고 규칙 11 이 잡는다), 총액 = 현금 + 보유 평가액.
 * 수비·미드필더·공격 = 그 턴 이전에 시작해 아직 열린 라인업 구간의 선언 포지션별 합이고,
 * 골키퍼는 총액 − (수비 + 미드필더 + 공격)의 잔여 방식이다 — 직접 합이면 라인업이 빠진 보유가 있을 때
 * {@code ck_turn_position_sum} 이 INSERT 에서 터져 대사 없이 멈춘다. 직접 합은 규칙 19 가 맡는다.
 * 고점은 max(초기 자금, 이전 턴 총액 최대, 이번 총액)이고 낙폭 반올림은 {@link MoneyMath#drawdown} 한 곳이다.
 * 조회는 보유 종목 수와 무관한 목록 3개뿐이다(가격은 호출자가 준 메모리 맵).
 */
@Component
public class TurnSnapshotCalculator {

    private final HoldingRepository holdings;
    private final LineupRepository lineups;
    private final TurnRepository turns;

    public TurnSnapshotCalculator(HoldingRepository holdings, LineupRepository lineups, TurnRepository turns) {
        this.holdings = holdings;
        this.lineups = lineups;
        this.turns = turns;
    }

    /**
     * @param prices 그 턴의 종목별 가격(방금 계산한 풀 전체)
     */
    public TurnSnapshot snapshot(Season season, CashAccount account, int turnNo, Map<Long, BigDecimal> prices) {
        Long seasonId = season.getId();
        Map<Long, Integer> quantityByAsset = new HashMap<>();
        BigDecimal holdingsValue = BigDecimal.ZERO;
        for (Holding holding : holdings.findBySeasonId(seasonId)) {
            quantityByAsset.put(holding.getAssetId(), holding.getQuantity());
            BigDecimal price = prices.get(holding.getAssetId());
            if (price != null) {
                holdingsValue = holdingsValue.add(MoneyMath.valuation(price, holding.getQuantity()));
            }
        }
        BigDecimal total = MoneyMath.totalValue(account.getBalance(), List.of(holdingsValue));

        BigDecimal defender = BigDecimal.ZERO;
        BigDecimal midfielder = BigDecimal.ZERO;
        BigDecimal forward = BigDecimal.ZERO;
        for (Lineup lineup : lineups.findOpenBySeasonIdStartedBefore(seasonId, turnNo)) {
            Integer quantity = quantityByAsset.get(lineup.getAssetId());
            BigDecimal price = prices.get(lineup.getAssetId());
            if (quantity == null || price == null) {
                continue;
            }
            BigDecimal value = MoneyMath.valuation(price, quantity);
            switch (lineup.getDeclaredPosition()) {
                case DEFENDER -> defender = defender.add(value);
                case MIDFIELDER -> midfielder = midfielder.add(value);
                case FORWARD_TARGET, FORWARD_BREAKOUT, FORWARD_DEFENSIVE -> forward = forward.add(value);
                case GOALKEEPER -> {
                    // 골키퍼는 잔여 방식이라 따로 더하지 않는다.
                }
            }
        }
        BigDecimal goalkeeper = total.subtract(defender).subtract(midfielder).subtract(forward);

        BigDecimal peak = season.getInitialCash().max(total);
        BigDecimal previousMax = turns.findMaxTotalValueBefore(seasonId, turnNo).orElse(BigDecimal.ZERO);
        peak = peak.max(previousMax);
        return new TurnSnapshot(account.getBalance(), holdingsValue, total, goalkeeper, defender, midfielder, forward,
                MoneyMath.drawdown(total, peak));
    }
}
