package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 시즌 한 개. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열로 나간다 — scale 은 {@link #from} 한 곳에서 정한다.
 * 시드·엔진 버전·기준일·스냅샷 날짜·회원 번호·버전·시각은 싣지 않는다.
 */
public record SeasonDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌 식별자")
        Long id,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        SeasonStatus status,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현재 턴(0 = 시작)")
        Integer currentTurnNo,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "위험 선언, 소수 8자리(-0.20000000 = -20%)")
        BigDecimal riskLimit,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto startFormation,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto currentFormation,
        @ExposedFrom("season.initial_cash")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "초기 자금, 원 단위 정수")
        BigDecimal initialCash,
        @ExposedFrom("cash_account.balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현금 잔액, 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom("season.fee_rate_kr")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "국내 수수료율, 소수 8자리")
        BigDecimal feeRateKr,
        @ExposedFrom("season.fee_rate_us")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미국 수수료율, 소수 8자리")
        BigDecimal feeRateUs,
        @ExposedFrom("season.fx_spread_rate")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "환전 비용률, 소수 8자리")
        BigDecimal fxSpreadRate) {

    public static SeasonDto from(Season s, BigDecimal cashBalance) {
        return new SeasonDto(s.getId(), s.getStatus(), s.getCurrentTurnNo(), s.getPathLength(),
                MoneyMath.exactRate(s.getRiskLimit()),
                FormationDto.from(s.getStartFormation()), FormationDto.from(s.getCurrentFormation()),
                MoneyMath.exactWon(s.getInitialCash()), MoneyMath.exactWon(cashBalance),
                MoneyMath.exactRate(s.getFeeRateKr()), MoneyMath.exactRate(s.getFeeRateUs()),
                MoneyMath.exactRate(s.getFxSpreadRate()));
    }
}
