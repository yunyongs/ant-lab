package antlab.account;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** Preview of one order. Money is a whole-KRW string; the scale is set in {@link #of} only. */
@Schema(description = "Preview of one market order at the current turn price. Nothing is written; the amounts are "
        + "what an order with the same body would fill now. Turn, date, seed and path are not included\n"
        + "\n"
        + "현재 턴 가격으로 본 시장가 주문 한 건의 미리보기. 아무것도 쓰지 않으며, 금액은 같은 본문으로 지금 주문하면 체결될 값이다. "
        + "턴·날짜·시드·경로는 싣지 않는다")
public record OrderQuoteDto(
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fill price (the current turn's price), whole "
                + "KRW / 체결가(현재 턴 가격), 원 단위 정수")
        BigDecimal fillPrice,
        @ExposedFrom("turn_price.price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Amount (fill price × quantity), whole KRW / "
                + "대금(체결가 × 수량), 원 단위 정수")
        BigDecimal notional,
        @ExposedFrom({"turn_price.price", "season.fee_rate_kr", "season.fee_rate_us", "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fee the fill would charge, FX cost included, "
                + "whole KRW / 체결 때 매길 수수료(환전 비용 포함), 원 단위 정수")
        BigDecimal fee,
        @ExposedFrom({"turn_price.price", "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "FX cost part of the fee for a US asset, "
                + "round(fill price × quantity × FX cost rate); 0 for a KR asset. Display only: it is already inside "
                + "fee and is not added to it\n"
                + "\n"
                + "미국 종목 수수료 중 환전 비용 몫, round(체결가 × 수량 × 환전 비용률). 국내 종목은 0. 표시용이며 fee 에 이미 들어 "
                + "있어 더하지 않는다")
        BigDecimal fxCost,
        @ExposedFrom({"cash_account.balance", "turn_price.price", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Cash balance after the fill, whole KRW / 체결 "
                + "뒤 현금 잔액, 원 단위 정수")
        BigDecimal cashAfter,
        @ExposedFrom({"cash_account.balance", "turn_price.price", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate", "holding.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Largest quantity a buy of this asset could fill now (cash with the fee, and the "
                        + "single-asset limit when it is set). null for a sell\n"
                        + "\n"
                        + "이 종목을 지금 살 수 있는 최대 수량(수수료를 포함한 현금, 단일 종목 한도가 있으면 그 한도까지). 매도는 null")
        Integer maxBuyQuantity) {

    static OrderQuoteDto of(BigDecimal fillPrice, BigDecimal notional, BigDecimal fee, BigDecimal fxCost,
            BigDecimal cashAfter, Integer maxBuyQuantity) {
        return new OrderQuoteDto(MoneyMath.exactWon(fillPrice), MoneyMath.exactWon(notional), MoneyMath.exactWon(fee),
                MoneyMath.exactWon(fxCost), MoneyMath.exactWon(cashAfter), maxBuyQuantity);
    }
}
