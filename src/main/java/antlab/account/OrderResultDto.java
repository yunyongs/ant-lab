package antlab.account;

import antlab.asset.Position;
import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 체결 결과 한 건(매수·매도). 금액은 원 단위 정수 문자열, 평균단가는 소수 4자리 문자열이다 — scale 은 {@link #of} 한 곳에서 정한다.
 * 기록 시각·출발점·유형·상태·피드 번호·시드·경로·연월은 싣지 않는다.
 */
@Schema(description = "Recorded time, origin, kind, status, feed number, seed, path and year-month are not "
        + "included\n"
        + "\n"
        + "기록 시각·출발점·유형·상태·피드 번호·시드·경로·연월은 싣지 않는다")
public record OrderResultDto(
        @ExposedFrom("orders.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Order identifier / 주문 식별자")
        Long orderId,
        @ExposedFrom("orders.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The turn of the order / 주문한 턴")
        Integer turnNo,
        @ExposedFrom("orders.side")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        OrderSide side,
        @ExposedFrom("turn_price.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in "
                + "the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)")
        Long assetId,
        @ExposedFrom("execution.quantity")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Filled quantity / 체결 수량")
        Integer quantity,
        @ExposedFrom("execution.fill_price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fill price (that turn's price), whole KRW / "
                + "체결가(그 턴 가격), 원 단위 정수")
        BigDecimal fillPrice,
        @ExposedFrom({"execution.fill_price", "execution.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Amount (fill price × quantity), whole KRW / "
                + "대금(체결가 × 수량), 원 단위 정수")
        BigDecimal notional,
        @ExposedFrom("execution.fee")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fee, whole KRW / 수수료, 원 단위 정수")
        BigDecimal fee,
        @ExposedFrom("cash_account.balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Cash balance after the fill, whole KRW / 체결 "
                + "뒤 현금 잔액, 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom("holding.quantity")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Quantity held after the fill / 체결 뒤 보유 수량")
        Integer holdingQuantity,
        @ExposedFrom("holding.avg_price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Average cost after the fill (fee excluded), "
                + "4 decimal places / 체결 뒤 평균단가(수수료 제외), 소수 4자리")
        BigDecimal avgPrice,
        @ExposedFrom("execution.realized_pnl")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Realized P&L (fill price − average cost just before the fill) × quantity, a "
                        + "string with 4 decimal places, can be negative. null for a buy\n"
                        + "\n"
                        + "실현 손익(체결가 − 체결 직전 평균단가) × 수량, 소수 4자리 문자열, 음수 가능. 매수는 null")
        BigDecimal realizedPnl,
        @ExposedFrom("lineup.declared_position")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Declared position of this asset's span (the "
                + "span just closed if everything was sold) / 이 종목의 구간 선언 포지션(전량 매도면 방금 닫은 구간)")
        Position declaredPosition,
        @ExposedFrom("orders.formation_change_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Identifier of the last formation change of that turn. null if there was no change "
                        + "/ 그 턴의 마지막 전술 변경 식별자. 변경이 없으면 null")
        Long formationChangeId) {

    static OrderResultDto of(Order order, Execution execution, BigDecimal notional, BigDecimal cashBalance,
            Holding holding, Position declaredPosition) {
        return new OrderResultDto(order.getId(), order.getTurnNo(), order.getSide(), order.getAssetId(),
                execution.getQuantity(), MoneyMath.exactWon(execution.getFillPrice()), MoneyMath.exactWon(notional),
                MoneyMath.exactWon(execution.getFee()), MoneyMath.exactWon(cashBalance), holding.getQuantity(),
                MoneyMath.exactAvgPrice(holding.getAvgPrice()), MoneyMath.exactPnl(execution.getRealizedPnl()),
                declaredPosition, order.getFormationChangeId());
    }
}
