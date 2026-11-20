package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.common.money.MoneyMath;
import antlab.member.MemberKind;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시즌 한 개. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열로 나간다 — scale 은 {@link #from} 한 곳에서 정한다.
 * 시드·엔진 버전·기준일·스냅샷 날짜·회원 번호·버전·시각은 싣지 않는다.
 */
@Schema(description = "Seed, engine version, reference date, snapshot date, member number, version and times are "
        + "not included\n"
        + "\n"
        + "시드·엔진 버전·기준일·스냅샷 날짜·회원 번호·버전·시각은 싣지 않는다")
public record SeasonDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season identifier / 시즌 식별자")
        Long id,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        SeasonStatus status,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Current turn (0 = start) / 현재 턴(0 = 시작)")
        Integer currentTurnNo,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Path length (number of turns) / 경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk limit, 8 decimal places (-0.20000000 = "
                + "-20%) / 위험 선언, 소수 8자리(-0.20000000 = -20%)")
        BigDecimal riskLimit,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto startFormation,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto currentFormation,
        @ExposedFrom("season.initial_cash")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Initial capital, whole KRW / 초기 자금, 원 단위 정수")
        BigDecimal initialCash,
        @ExposedFrom("cash_account.balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Cash balance, whole KRW / 현금 잔액, 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom("season.fee_rate_kr")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Domestic fee rate, 8 decimal places / 국내 "
                + "수수료율, 소수 8자리")
        BigDecimal feeRateKr,
        @ExposedFrom("season.fee_rate_us")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "US fee rate, 8 decimal places / 미국 수수료율, 소수 "
                + "8자리")
        BigDecimal feeRateUs,
        @ExposedFrom("season.fx_spread_rate")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Currency exchange cost rate, 8 decimal "
                + "places / 환전 비용률, 소수 8자리")
        BigDecimal fxSpreadRate,
        @ExposedFrom("season.abandoned_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "The turn at which the "
                + "season was abandoned. null if it was not abandoned / 포기한 턴. 포기하지 않았으면 null")
        Integer abandonedTurnNo,
        @ExposedFrom("season.abandoned_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True for a human member who abandoned before "
                + "turn 3 (derived from the abandon turn and the member kind; not stored) / 사람 회원이 3턴 전에 포기하면 "
                + "true(포기 턴과 회원 종류에서 계산, 저장하지 않는다)")
        Boolean notStarted) {

    /** A season that was just started: nothing is abandoned yet, so the member kind does not matter. */
    public static SeasonDto forNewSeason(Season s, BigDecimal cashBalance) {
        return from(s, cashBalance, MemberKind.HUMAN);
    }

    /** The stored state, for a season the caller has just changed. Reads use the overload with a clock. */
    public static SeasonDto from(Season s, BigDecimal cashBalance, MemberKind memberKind) {
        return from(s, cashBalance, memberKind, s.getStatus(), s.getAbandonedTurnNo());
    }

    /** The state a read shows: an overdue pause is already an abandon (O-2). */
    public static SeasonDto from(Season s, BigDecimal cashBalance, MemberKind memberKind, Instant now) {
        return from(s, cashBalance, memberKind, s.effectiveStatus(now), s.effectiveAbandonedTurnNo(now));
    }

    private static SeasonDto from(Season s, BigDecimal cashBalance, MemberKind memberKind, SeasonStatus status,
            Integer abandonedTurnNo) {
        return new SeasonDto(s.getId(), status, s.getCurrentTurnNo(), s.getPathLength(),
                MoneyMath.exactRate(s.getRiskLimit()),
                FormationDto.from(s.getStartFormation()), FormationDto.from(s.getCurrentFormation()),
                MoneyMath.exactWon(s.getInitialCash()), MoneyMath.exactWon(cashBalance),
                MoneyMath.exactRate(s.getFeeRateKr()), MoneyMath.exactRate(s.getFeeRateUs()),
                MoneyMath.exactRate(s.getFxSpreadRate()), abandonedTurnNo,
                AbandonRules.notStarted(memberKind, abandonedTurnNo));
    }
}
