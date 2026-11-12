package antlab.account;

import antlab.asset.Position;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** 보유 한 종목. 종목 이름은 싣지 않는다 — 풀 응답의 assetId 로 잇는다. 평균단가는 소수 4자리, 비중은 소수 8자리 문자열. */
@Schema(description = "One held asset. The asset name is not included — link it by assetId from the pool response\n"
        + "\n"
        + "보유 한 종목. 종목 이름은 싣지 않는다 — 풀 응답의 assetId 로 잇는다")
public record HoldingDto(
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in "
                + "the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("holding.quantity")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Quantity held (1 or more) / 보유 수량(1 이상)")
        Integer quantity,
        @ExposedFrom("holding.avg_price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Average cost (fee excluded), 4 decimal "
                + "places / 평균단가(수수료 제외), 소수 4자리")
        BigDecimal avgPrice,
        @ExposedFrom("lineup.declared_position")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Declared position of the open span / 열린 구간의 "
                + "선언 포지션")
        Position declaredPosition,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Current turn price, whole KRW / 현재 턴 가격, 원 "
                + "단위 정수")
        BigDecimal price,
        @ExposedFrom({"holding.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation = quantity × current turn price, "
                + "whole KRW / 평가액 = 수량 × 현재 턴 가격, 원 단위 정수")
        BigDecimal value,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset share = valuation ÷ total valuation "
                + "(cash included), 8 decimal places / 종목 비중 = 평가액 ÷ 총 평가액(현금 포함), 소수 8자리")
        BigDecimal weight) {
}
