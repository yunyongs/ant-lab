package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** The report's market benchmark at one played turn, so the in-season figure equals the finished-season one. */
@Schema(description = "The season report's benchmark (the market index proxies bought at turn 0 and held, same "
        + "initial capital and fees) up to this turn. At the last turn of a finished season the values equal the "
        + "report's baselineFinalValue, baselineReturn and excessReturn\n"
        + "\n"
        + "성적표의 기준선(시장 지수 대용 종목을 0턴에 사서 보유, 같은 초기 자금과 수수료)을 이 턴까지 계산한 값. 끝난 시즌의 마지막 턴에서는 "
        + "성적표의 baselineFinalValue·baselineReturn·excessReturn 과 같다")
public record BenchmarkToDateDto(
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Benchmark valuation at this turn, whole KRW / "
                + "이 턴의 기준선 평가액, 원 단위 정수")
        BigDecimal value,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Benchmark valuation at the previous turn "
                + "(initial capital at turn 1, as for the team), whole KRW / 직전 턴의 기준선 평가액(1턴이면 팀과 같게 초기 "
                + "자금), 원 단위 정수")
        BigDecimal previousValue,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Valuation − previous valuation, whole KRW "
                + "(can be negative) / 평가액 − 직전 평가액, 원 단위 정수(음수 가능)")
        BigDecimal valueChange,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Period return = change ÷ previous valuation, 8 "
                + "decimal places / 기간 수익률 = 변화 ÷ 직전 평가액, 소수 8자리")
        BigDecimal periodReturn,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Return to date = (valuation − initial capital) "
                + "÷ initial capital, 8 decimal places / 누적 수익률 = (평가액 − 초기 자금) ÷ 초기 자금, 소수 8자리")
        BigDecimal totalReturn,
        @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "season.fee_rate_kr",
                "season.fee_rate_us", "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Excess return to date = (team total valuation − "
                + "benchmark valuation) ÷ initial capital, divided once — not the difference of two returns. 8 decimal "
                + "places / 누적 초과 수익률 = (팀 총 평가액 − 기준선 평가액) ÷ 초기 자금, 한 번 나눔 — 두 수익률의 차가 아니다. "
                + "소수 8자리")
        BigDecimal excessReturn) {
}
