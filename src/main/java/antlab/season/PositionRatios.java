package antlab.season;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;

/**
 * 포지션 비중·목표 대비 차이·포메이션 이탈 — 턴 결과와 보유 조회가 같은 식을 쓴다(저장소 없음).
 * 모든 비율은 {@link MoneyMath#ratio} 한 곳에서 정수 ÷ 정수를 소수 8자리로 한 번만 반올림한다(db/26 {@code sr_div8} 의 짝).
 * 차이는 반올림한 비중에서 목표를 빼지 않고 {@code (100·v − f·V) ÷ (100·V)} 를 한 번에 나눈다 —
 * 0에서 먼 쪽 반올림은 부호가 바뀌는 경계에서 "반올림 뒤 빼기"와 갈릴 수 있기 때문이다. 양수 = 목표보다 많음.
 * 이탈 = Σ|100·v − f·V| ÷ (100·V) (db/26 {@code sr_turn_base} 의 준수 분자/분모).
 */
public record PositionRatios(
        BigDecimal goalkeeperWeight, BigDecimal defenderWeight, BigDecimal midfielderWeight, BigDecimal forwardWeight,
        BigDecimal goalkeeperGap, BigDecimal defenderGap, BigDecimal midfielderGap, BigDecimal forwardGap,
        BigDecimal formationDeviation) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 포지션별 평가액·총액은 원 단위 정수, 총액은 0이 아니다. */
    public static PositionRatios of(BigDecimal goalkeeper, BigDecimal defender, BigDecimal midfielder,
            BigDecimal forward, BigDecimal total, Formation target) {
        BigDecimal denominator = HUNDRED.multiply(total);
        BigDecimal gk = diff(goalkeeper, target.goalkeeper(), total);
        BigDecimal df = diff(defender, target.defender(), total);
        BigDecimal mf = diff(midfielder, target.midfielder(), total);
        BigDecimal fw = diff(forward, target.forward(), total);
        BigDecimal deviation = gk.abs().add(df.abs()).add(mf.abs()).add(fw.abs());
        return new PositionRatios(
                MoneyMath.ratio(goalkeeper, total), MoneyMath.ratio(defender, total),
                MoneyMath.ratio(midfielder, total), MoneyMath.ratio(forward, total),
                MoneyMath.ratio(gk, denominator), MoneyMath.ratio(df, denominator),
                MoneyMath.ratio(mf, denominator), MoneyMath.ratio(fw, denominator),
                MoneyMath.ratio(deviation, denominator));
    }

    /** 100·v − f·V (정수) */
    private static BigDecimal diff(BigDecimal value, int targetPercent, BigDecimal total) {
        return HUNDRED.multiply(value).subtract(BigDecimal.valueOf(targetPercent).multiply(total));
    }
}
