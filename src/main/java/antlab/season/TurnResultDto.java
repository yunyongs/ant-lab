package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 턴 결과 — 저장한 {@code turn} 행의 값과 거기서 계산한 비율이다. 턴 전환 응답({@code POST …/turns})과 턴 조회({@code GET …/turns/{n}})가
 * 같은 조립기({@link TurnResultAssembler})로 이 한 형을 만든다. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열.
 * 비중 4개의 합은 각각 한 번 반올림해 1과 최대 2e-8 다를 수 있다. {@code status}·{@code pathLength} 는 시즌의 <em>지금</em> 값이다.
 *
 * {@code targetFormation} 은 그 턴 <b>시작 시점</b>의 목표(그 턴 이전의 마지막 전술 변경의 이후 값, 없으면 시작 포메이션)다.
 * 대사 시도 번호·상태·시드·경로·월 블록·연월·수익률 종류는 싣지 않는다.
 */
@Schema(description = "저장한 턴 행의 값과 거기서 계산한 비율. 턴 전환 응답과 턴 조회가 같은 형이다. 비중 4개의 합은 1과 최대 2e-8 다를 수 있고, status·pathLength 는 시즌의 지금 값이다. targetFormation 은 그 턴 시작 시점의 목표다. 대사 시도 번호·상태·시드·경로·월 블록·연월·수익률 종류는 싣지 않는다")
public record TurnResultDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌 식별자")
        Long seasonId,
        @ExposedFrom("turn.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "이 결과의 턴(전환 응답에서는 방금 적용된 턴 = 새 현재 턴)")
        Integer turnNo,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "마지막 턴이면 FINISHED")
        SeasonStatus status,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("turn.cash_balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현금 잔액, 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom("turn.holdings_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보유 평가액, 원 단위 정수")
        BigDecimal holdingsValue,
        @ExposedFrom("turn.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "총 평가액, 원 단위 정수")
        BigDecimal totalValue,
        @ExposedFrom("turn.goalkeeper_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 평가액(현금 포함), 원 단위 정수")
        BigDecimal goalkeeperValue,
        @ExposedFrom("turn.defender_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 평가액, 원 단위 정수")
        BigDecimal defenderValue,
        @ExposedFrom("turn.midfielder_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 평가액, 원 단위 정수")
        BigDecimal midfielderValue,
        @ExposedFrom("turn.forward_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 평가액, 원 단위 정수")
        BigDecimal forwardValue,
        @ExposedFrom("turn.drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "누적 낙폭(0 이하), 소수 8자리")
        BigDecimal drawdown,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "직전 턴의 총 평가액(1턴이면 초기 자금), 원 단위 정수")
        BigDecimal previousTotalValue,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "총 평가액 − 직전 총 평가액(음수 가능), 원 단위 정수")
        BigDecimal valueChange,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "기간 수익률 = 변화 ÷ 직전 총 평가액, 소수 8자리")
        BigDecimal periodReturn,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "위험 선언(0 이하), 소수 8자리")
        BigDecimal riskLimit,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "위험 선언까지의 여유 = 낙폭 − 선언, 소수 8자리. 0 이상이면 선언 안, 음수면 넘었다")
        BigDecimal riskHeadroom,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "낙폭이 위험 선언을 넘었는가(낙폭 < 선언)")
        Boolean riskBreached,
        @ExposedFrom({"turn.goalkeeper_value", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 비중 = 평가액 ÷ 총 평가액, 소수 8자리")
        BigDecimal goalkeeperWeight,
        @ExposedFrom({"turn.defender_value", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 비중, 소수 8자리")
        BigDecimal defenderWeight,
        @ExposedFrom({"turn.midfielder_value", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 비중, 소수 8자리")
        BigDecimal midfielderWeight,
        @ExposedFrom({"turn.forward_value", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 비중, 소수 8자리")
        BigDecimal forwardWeight,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto targetFormation,
        @ExposedFrom({"turn.goalkeeper_value", "turn.total_value", "season.start_goalkeeper", "season.current_goalkeeper","formation_change.after_goalkeeper"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 비중 − 목표(양수 = 목표보다 많음), 소수 8자리")
        BigDecimal goalkeeperGap,
        @ExposedFrom({"turn.defender_value", "turn.total_value", "season.start_defender", "season.current_defender", "formation_change.after_defender"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 비중 − 목표, 소수 8자리")
        BigDecimal defenderGap,
        @ExposedFrom({"turn.midfielder_value", "turn.total_value", "season.start_midfielder", "season.current_midfielder", "formation_change.after_midfielder"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 비중 − 목표, 소수 8자리")
        BigDecimal midfielderGap,
        @ExposedFrom({"turn.forward_value", "turn.total_value", "season.start_forward", "season.current_forward", "formation_change.after_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 비중 − 목표, 소수 8자리")
        BigDecimal forwardGap,
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value",
                "turn.total_value", "season.start_goalkeeper", "season.start_defender", "season.start_midfielder", "season.start_forward",
                "season.current_goalkeeper", "season.current_defender", "season.current_midfielder", "season.current_forward",
                "formation_change.after_goalkeeper", "formation_change.after_defender", "formation_change.after_midfielder", "formation_change.after_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "포메이션 이탈 = 포지션별 |비중 − 목표|의 합, 소수 8자리")
        BigDecimal formationDeviation) {
}
