package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** One position slot of a turn: stored valuations of this and the previous turn, and the price effect of its holdings. */
@Schema(description = "One position of this turn: its stored valuation now and at the previous turn, and what the "
        + "price change of its holdings added. valueChange also moves with trades between positions and cash; "
        + "contribution is the price effect only\n"
        + "\n"
        + "이 턴의 포지션 하나: 지금과 직전 턴의 저장된 평가액, 그 포지션 보유 종목의 가격 변화가 더한 금액. valueChange 는 포지션과 "
        + "현금 사이의 매매로도 움직이고, contribution 은 가격 효과만이다")
public record PositionContributionDto(
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation at this turn, whole KRW / 이 턴의 "
                + "평가액, 원 단위 정수")
        BigDecimal value,
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value",
                "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation at the previous turn (at turn 1, "
                + "the initial capital for the goalkeeper, 0 for the others), whole KRW / 직전 턴의 평가액(1턴이면 골키퍼는 "
                + "초기 자금, 나머지는 0), 원 단위 정수")
        BigDecimal previousValue,
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value",
                "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation − previous valuation, whole KRW "
                + "(can be negative) / 평가액 − 직전 평가액, 원 단위 정수(음수 가능)")
        BigDecimal valueChange,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price",
                "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sum of the holding contributions declared in "
                + "this position, whole KRW (cash adds 0) / 이 포지션으로 선언한 보유 기여의 합, 원 단위 정수(현금은 0)")
        BigDecimal contribution,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price",
                "lineup.declared_position", "turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Contribution ÷ previous total valuation, 8 "
                + "decimal places / 기여 ÷ 직전 총 평가액, 소수 8자리")
        BigDecimal contributionReturn) {
}
