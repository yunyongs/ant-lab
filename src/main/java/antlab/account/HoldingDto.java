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
        BigDecimal weight,
        @ExposedFrom({"holding.quantity", "holding.avg_price", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Unrealised P&L = valuation − quantity × average "
                + "cost (fees and FX cost excluded; future sell fee and tax not deducted), rounded once, whole KRW "
                + "(negative = loss). Display only. The per-holding values can differ from the total by a few won / "
                + "평가손익 = 평가액 − 수량 × 평균단가(수수료·환전 비용 제외, 앞으로의 매도 수수료·세금은 빼지 않음), 한 번 반올림, "
                + "원 단위 정수(음수 = 손실). 표시용. 종목별 값의 합이 전체 값과 몇 원 다를 수 있다")
        BigDecimal unrealizedPnl,
        @ExposedFrom({"holding.quantity", "holding.avg_price", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Unrealised return = unrealised P&L ÷ (quantity × "
                + "average cost), 8 decimal places (−0.1 = −10%). Display only / 평가수익률 = 평가손익 ÷ (수량 × 평균단가), "
                + "소수 8자리(−0.1 = −10%). 표시용")
        BigDecimal unrealizedReturn) {
}
