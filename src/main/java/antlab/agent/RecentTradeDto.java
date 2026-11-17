package antlab.agent;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** One fill of the agent in a recent turn. Money is whole KRW as a plain string, like the human order result. */
public record RecentTradeDto(
        @ExposedFrom("orders.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Integer turnNo,
        @ExposedFrom("orders.side")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        OrderSide side,
        @ExposedFrom({"asset.market", "asset.ticker"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "MARKET:TICKER, for example KR:005930")
        String asset,
        @ExposedFrom("execution.quantity")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Integer quantity,
        @ExposedFrom("execution.fill_price")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fill price (that turn's price), whole KRW")
        BigDecimal price,
        @ExposedFrom("execution.fee")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fee, whole KRW")
        BigDecimal fee,
        @ExposedFrom("orders.source")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        OrderSource source) {
}
