package antlab.scoring;

import antlab.asset.Position;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** One asset held through the move into the turn and its price effect. No asset name — link it by assetId. */
@Schema(description = "One asset held through the move into this turn (bought before this turn) and what its price "
        + "change added. The asset name is not included — link it by assetId from the pool response\n"
        + "\n"
        + "이 턴으로 넘어오는 동안 보유한 한 종목(이 턴 전에 산 것)과 가격 변화가 더한 금액. 종목 이름은 싣지 않는다 — 풀 응답의 "
        + "assetId 로 잇는다")
public record HoldingContributionDto(
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in "
                + "the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("lineup.declared_position")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Declared position during this turn / 이 턴 동안의 "
                + "선언 포지션")
        Position declaredPosition,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Quantity held through the move (fills before "
                + "this turn), 1 or more / 넘어오는 동안의 보유 수량(이 턴 전의 체결), 1 이상")
        Integer quantity,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price of the previous turn, whole KRW / 직전 턴 "
                + "가격, 원 단위 정수")
        BigDecimal previousPrice,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price of this turn, whole KRW / 이 턴 가격, 원 단위 "
                + "정수")
        BigDecimal price,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation = quantity × this turn's price, whole "
                + "KRW / 평가액 = 수량 × 이 턴 가격, 원 단위 정수")
        BigDecimal value,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Contribution = quantity × (price − previous "
                + "price), whole KRW (can be negative) / 기여 = 수량 × (가격 − 직전 가격), 원 단위 정수(음수 가능)")
        BigDecimal contribution,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Price return = (price − previous price) ÷ "
                + "previous price, 8 decimal places / 가격 수익률 = (가격 − 직전 가격) ÷ 직전 가격, 소수 8자리")
        BigDecimal priceReturn,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price", "turn.total_value",
                "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Contribution ÷ previous total valuation, 8 "
                + "decimal places — this asset's part of the period return / 기여 ÷ 직전 총 평가액, 소수 8자리 — 기간 "
                + "수익률 중 이 종목의 몫")
        BigDecimal contributionReturn) {
}
