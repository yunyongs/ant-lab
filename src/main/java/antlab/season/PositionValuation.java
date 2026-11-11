package antlab.season;

import antlab.asset.Position;
import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.List;

/**
 * 포지션별 평가액 계산 — 턴 스냅샷({@link TurnSnapshotCalculator})과 보유 조회가 같은 식을 쓰게 떼어 낸 순수 부품이다(저장소 없음).
 * 보유 평가액 = Σ 수량 × 가격(가격 없는 보유는 조용히 뺀다 — 규칙 11 이 잡는다), 총액 = 현금 + 보유 평가액.
 * 수비·미드필더·공격은 선언 포지션별 합이고 골키퍼는 총액 − (수비 + 미드필더 + 공격)의 잔여 방식이다 —
 * 직접 합이면 선언이 빠진 보유가 있을 때 {@code ck_turn_position_sum} 이 터진다(직접 합은 규칙 19 가 맡는다).
 */
public final class PositionValuation {

    /** 보유 한 종목: 수량, 그 턴 가격(없으면 null), 선언 포지션(열린 라인업이 없으면 null). */
    public record Line(Long assetId, int quantity, BigDecimal price, Position declaredPosition) {
    }

    public record PositionValues(BigDecimal holdingsValue, BigDecimal total, BigDecimal goalkeeper,
            BigDecimal defender, BigDecimal midfielder, BigDecimal forward) {
    }

    private PositionValuation() {
    }

    public static PositionValues of(BigDecimal cash, List<Line> lines) {
        BigDecimal holdingsValue = BigDecimal.ZERO;
        BigDecimal defender = BigDecimal.ZERO;
        BigDecimal midfielder = BigDecimal.ZERO;
        BigDecimal forward = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.price() == null) {
                continue;
            }
            BigDecimal value = MoneyMath.valuation(line.price(), line.quantity());
            holdingsValue = holdingsValue.add(value);
            if (line.declaredPosition() == null) {
                continue;
            }
            switch (line.declaredPosition()) {
                case DEFENDER -> defender = defender.add(value);
                case MIDFIELDER -> midfielder = midfielder.add(value);
                case FORWARD_TARGET, FORWARD_BREAKOUT, FORWARD_DEFENSIVE -> forward = forward.add(value);
                case GOALKEEPER -> {
                    // 골키퍼는 잔여 방식이라 따로 더하지 않는다.
                }
            }
        }
        BigDecimal total = MoneyMath.totalValue(cash, List.of(holdingsValue));
        BigDecimal goalkeeper = total.subtract(defender).subtract(midfielder).subtract(forward);
        return new PositionValues(holdingsValue, total, goalkeeper, defender, midfielder, forward);
    }
}
