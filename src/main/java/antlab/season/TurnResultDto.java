package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 턴 전환 결과 — 방금 저장한 {@code turn} 행의 값이다(계산을 두 번 하지 않는다). 금액은 원 단위 정수 문자열, 낙폭은 소수 8자리 문자열.
 * 대사 시도 번호·상태·시드·경로·월 블록은 싣지 않는다.
 */
public record TurnResultDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌 식별자")
        Long seasonId,
        @ExposedFrom("turn.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "방금 적용된 턴(= 새 현재 턴)")
        Integer turnNo,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "마지막 턴이면 FINISHED")
        SeasonStatus status,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("turn.cash_balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현금 잔액, 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom("turn.holdings_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보유 평가액, 원 단위 정수")
        BigDecimal holdingsValue,
        @ExposedFrom("turn.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "총 평가액, 원 단위 정수")
        BigDecimal totalValue,
        @ExposedFrom("turn.goalkeeper_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 평가액(현금 포함), 원 단위 정수")
        BigDecimal goalkeeperValue,
        @ExposedFrom("turn.defender_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 평가액, 원 단위 정수")
        BigDecimal defenderValue,
        @ExposedFrom("turn.midfielder_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 평가액, 원 단위 정수")
        BigDecimal midfielderValue,
        @ExposedFrom("turn.forward_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 평가액, 원 단위 정수")
        BigDecimal forwardValue,
        @ExposedFrom("turn.drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "누적 낙폭(0 이하), 소수 8자리")
        BigDecimal drawdown) {

    /** 전환을 끝낸 시즌과 방금 저장한 턴 행에서 만든다. scale 은 이 한 곳에서 정한다. */
    public static TurnResultDto of(Season season, Turn turn) {
        return new TurnResultDto(season.getId(), turn.getTurnNo(), season.getStatus(), season.getPathLength(),
                MoneyMath.exactWon(turn.getCashBalance()), MoneyMath.exactWon(turn.getHoldingsValue()),
                MoneyMath.exactWon(turn.getTotalValue()), MoneyMath.exactWon(turn.getGoalkeeperValue()),
                MoneyMath.exactWon(turn.getDefenderValue()), MoneyMath.exactWon(turn.getMidfielderValue()),
                MoneyMath.exactWon(turn.getForwardValue()), MoneyMath.exactRate(turn.getDrawdown()));
    }
}
