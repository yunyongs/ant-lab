package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** One point of the in-season value line: the turn number and three numbers. No date, month block or feed. */
@Schema(description = "One turn of the value line, turn number, total valuation, cumulative drawdown and period "
        + "return. No date or month block / 가치 곡선의 한 턴, 턴 번호, 총 평가액, 누적 낙폭, 기간 수익률. 날짜·월 블록 없음")
public record TurnSummaryDto(
        @ExposedFrom("turn.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn number (0 = the start) / 턴 번호(0 = 시작)")
        Integer turnNo,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total valuation, whole KRW (turn 0 = initial "
                + "capital) / 총 평가액, 원 단위 정수(0턴 = 초기 자금)")
        BigDecimal totalValue,
        @ExposedFrom("turn.drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Cumulative drawdown (0 or less), 8 decimal "
                + "places; 0 at turn 0 / 누적 낙폭(0 이하), 소수 8자리. 0턴은 0")
        BigDecimal drawdown,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Period return = change ÷ previous total "
                + "valuation, 8 decimal places; 0 at turn 0 / 기간 수익률 = 변화 ÷ 직전 총 평가액, 소수 8자리. 0턴은 0")
        BigDecimal periodReturn) {
}
