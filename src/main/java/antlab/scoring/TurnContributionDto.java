package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Where the change of one played turn came from: price effect per holding and per position, the trading cost of the
 * previous turn, and the report's benchmark up to this turn. Display values only; nothing here is reconciled.
 */
@Schema(description = "Where the change of one played turn came from: what the price change of each holding and "
        + "each position added, the trading cost paid at the previous turn, and the season report's benchmark up "
        + "to this turn. valueChange = holdingsContribution − tradingCost. No date, month block or future turn\n"
        + "\n"
        + "진행한 턴 하나의 변화가 어디서 왔는지: 종목별·포지션별 가격 변화가 더한 금액, 직전 턴에 낸 매매 비용, 이 턴까지의 성적표 기준선. "
        + "valueChange = holdingsContribution − tradingCost. 날짜·월 블록·미래 턴은 없다")
public record TurnContributionDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season identifier / 시즌 식별자")
        Long seasonId,
        @ExposedFrom("turn.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The turn of this result / 이 결과의 턴")
        Integer turnNo,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total valuation of the previous turn (initial "
                + "capital at turn 1), whole KRW / 직전 턴의 총 평가액(1턴이면 초기 자금), 원 단위 정수")
        BigDecimal previousTotalValue,
        @ExposedFrom("turn.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total valuation, whole KRW / 총 평가액, 원 단위 정수")
        BigDecimal totalValue,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total valuation − previous total valuation, "
                + "whole KRW (can be negative) / 총 평가액 − 직전 총 평가액, 원 단위 정수(음수 가능)")
        BigDecimal valueChange,
        @ExposedFrom({"execution.quantity", "orders.side", "orders.turn_no", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sum of the holding contributions (price effect), "
                + "whole KRW / 종목 기여(가격 효과)의 합, 원 단위 정수")
        BigDecimal holdingsContribution,
        @ExposedFrom({"execution.fee", "execution.tax", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fees, FX costs and taxes of the orders placed at "
                + "the previous turn (the trades that set the holdings of this turn), whole KRW, 0 or more / 직전 턴에 낸 "
                + "주문(이 턴의 보유를 만든 매매)의 수수료·환전 비용·세금, 원 단위 정수, 0 이상")
        BigDecimal tradingCost,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Team return to date = (total valuation − initial "
                + "capital) ÷ initial capital, 8 decimal places / 팀 누적 수익률 = (총 평가액 − 초기 자금) ÷ 초기 자금, 소수 "
                + "8자리")
        BigDecimal totalReturn,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        PositionContributionsDto positions,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Holdings through the move into this turn, "
                + "largest contribution first (ties by assetId); empty when nothing was held / 이 턴으로 넘어오는 동안의 "
                + "보유, 기여가 큰 순(같으면 assetId 순). 보유가 없었으면 빈 목록")
        List<HoldingContributionDto> holdings,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        BenchmarkToDateDto benchmark) {
}
