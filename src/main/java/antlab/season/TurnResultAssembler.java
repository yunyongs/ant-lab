package antlab.season;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;

/**
 * 턴 결과 조립기 — 순수 계산이다(저장소 없음). 턴 전환 응답과 턴 조회(그리고 이후 에이전트 TurnView)가 이 한 곳에서 같은
 * {@link TurnResultDto} 를 만든다(erd §9 "같은 조립 코드의 출력"). 금액·비율의 scale 은 여기서 정한다.
 *
 * <p>입력은 저장된 값뿐이다: 그 턴의 {@code turn} 행, 직전 총액(1턴이면 초기 자금), 그 턴 <b>시작 시점</b>의 목표 포메이션.
 * 비율은 {@link PositionRatios}, 기간 수익률은 {@link MoneyMath#ratio}, 위험 여유는 정확한 뺄셈이다(반올림 없음).
 */
public final class TurnResultAssembler {

    private TurnResultAssembler() {
    }

    public static TurnResultDto assemble(Season season, Turn turn, BigDecimal previousTotal, Formation target) {
        BigDecimal total = turn.getTotalValue();
        BigDecimal change = total.subtract(previousTotal);
        BigDecimal riskLimit = season.getRiskLimit();
        PositionRatios ratios = PositionRatios.of(turn.getGoalkeeperValue(), turn.getDefenderValue(),
                turn.getMidfielderValue(), turn.getForwardValue(), total, target);
        return new TurnResultDto(season.getId(), turn.getTurnNo(), season.getStatus(), season.getPathLength(),
                MoneyMath.exactWon(turn.getCashBalance()), MoneyMath.exactWon(turn.getHoldingsValue()),
                MoneyMath.exactWon(total), MoneyMath.exactWon(turn.getGoalkeeperValue()),
                MoneyMath.exactWon(turn.getDefenderValue()), MoneyMath.exactWon(turn.getMidfielderValue()),
                MoneyMath.exactWon(turn.getForwardValue()), MoneyMath.exactRate(turn.getDrawdown()),
                MoneyMath.exactWon(previousTotal), MoneyMath.exactWon(change), MoneyMath.ratio(change, previousTotal),
                MoneyMath.exactRate(riskLimit), MoneyMath.exactRate(turn.getDrawdown().subtract(riskLimit)),
                turn.getDrawdown().compareTo(riskLimit) < 0,
                ratios.goalkeeperWeight(), ratios.defenderWeight(), ratios.midfielderWeight(), ratios.forwardWeight(),
                FormationDto.from(target),
                ratios.goalkeeperGap(), ratios.defenderGap(), ratios.midfielderGap(), ratios.forwardGap(),
                ratios.formationDeviation());
    }
}
