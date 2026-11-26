package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** One point of the in-season value line: the turn number and four numbers. No date, month block or feed. */
@Schema(description = "One turn of the value line, turn number, total valuation, cumulative drawdown, period return and "
        + "market benchmark value. No date or month block / 가치 곡선의 한 턴, 턴 번호, 총 평가액, 누적 낙폭, 기간 수익률, 시장 기준선 평가액. 날짜·월 블록 없음")
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
        BigDecimal periodReturn,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Market benchmark valuation at this turn (the market index proxies bought at turn 0 and "
                        + "held, same initial capital and fees), whole KRW; initial capital at turn 0. Equals "
                        + "benchmark.value of the turn contribution. Null on every item when the benchmark cannot be "
                        + "computed for the season / 이 턴의 시장 기준선 평가액(시장 지수 대용 종목을 0턴에 사서 보유, 같은 초기 자금과 "
                        + "수수료), 원 단위 정수. 0턴은 초기 자금. 턴 기여의 benchmark.value 와 같다. 시즌의 기준선을 계산할 수 "
                        + "없으면 모든 항목에서 null")
        BigDecimal benchmarkValue) {
}
