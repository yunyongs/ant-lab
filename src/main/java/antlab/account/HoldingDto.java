package antlab.account;

import antlab.asset.Position;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** 보유 한 종목. 종목 이름은 싣지 않는다 — 풀 응답의 assetId 로 잇는다. 평균단가는 소수 4자리, 비중은 소수 8자리 문자열. */
public record HoldingDto(
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("holding.quantity")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보유 수량(1 이상)")
        Integer quantity,
        @ExposedFrom("holding.avg_price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "평균단가(수수료 제외), 소수 4자리")
        BigDecimal avgPrice,
        @ExposedFrom("lineup.declared_position")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "열린 구간의 선언 포지션")
        Position declaredPosition,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현재 턴 가격, 원 단위 정수")
        BigDecimal price,
        @ExposedFrom({"holding.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "평가액 = 수량 × 현재 턴 가격, 원 단위 정수")
        BigDecimal value,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "종목 비중 = 평가액 ÷ 총 평가액(현금 포함), 소수 8자리")
        BigDecimal weight) {
}
