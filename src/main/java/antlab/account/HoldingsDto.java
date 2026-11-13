package antlab.account;

import antlab.common.exposure.ExposedFrom;
import antlab.season.FormationDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * 현재 팀 — 현재 턴의 보유 × 그 턴 가격이다(이번 턴의 매매가 바로 반영되는 실시간 값). 금액은 원 단위 정수 문자열,
 * 비율은 소수 8자리 문자열이다. 매매가 없는 턴이면 총액·보유 평가액·포지션별 평가액이 그 턴의 턴 결과와 같다.
 * {@code currentFormation} 은 지금 적용 중인 포메이션이라 이번 턴의 전술 변경이 바로 보인다. 비중 4개의 합은 각각 한 번 반올림해 1과 최대 2e-8 다를 수 있다. 시드·경로·월 블록·연월·수익률 종류는 싣지 않는다.
 */
@Schema(description = "현재 턴의 보유 × 그 턴 가격(이번 턴의 매매가 바로 반영되는 실시간 값). 매매가 없는 턴이면 총액·보유 평가액·포지션별 평가액이 그 턴의 턴 결과와 같다. currentFormation 은 지금 적용 중인 포메이션이다. 비중 4개의 합은 1과 최대 2e-8 다를 수 있다. 시드·경로·월 블록·연월·수익률 종류는 싣지 않는다")
public record HoldingsDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌 식별자")
        Long seasonId,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌의 현재 턴(가격 기준 턴)")
        Integer turnNo,
        @ExposedFrom("cash_account.balance")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "현금 잔액(지금), 원 단위 정수")
        BigDecimal cashBalance,
        @ExposedFrom({"holding.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보유 평가액 = Σ 수량 × 현재 턴 가격, 원 단위 정수")
        BigDecimal holdingsValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "총 평가액 = 현금 + 보유 평가액, 원 단위 정수")
        BigDecimal totalValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 평가액(현금 포함, 총액 − 나머지 셋), 원 단위 정수")
        BigDecimal goalkeeperValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 평가액, 원 단위 정수")
        BigDecimal defenderValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 평가액, 원 단위 정수")
        BigDecimal midfielderValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 평가액, 원 단위 정수")
        BigDecimal forwardValue,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 비중 = 평가액 ÷ 총 평가액, 소수 8자리")
        BigDecimal goalkeeperWeight,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 비중, 소수 8자리")
        BigDecimal defenderWeight,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 비중, 소수 8자리")
        BigDecimal midfielderWeight,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 비중, 소수 8자리")
        BigDecimal forwardWeight,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        FormationDto currentFormation,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position",
                "season.current_goalkeeper"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 비중 − 현재 포메이션(양수 = 목표보다 많음), 소수 8자리")
        BigDecimal goalkeeperGap,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position",
                "season.current_defender"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 비중 − 현재 포메이션, 소수 8자리")
        BigDecimal defenderGap,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position",
                "season.current_midfielder"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 비중 − 현재 포메이션, 소수 8자리")
        BigDecimal midfielderGap,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position",
                "season.current_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 비중 − 현재 포메이션, 소수 8자리")
        BigDecimal forwardGap,
        @ExposedFrom({"holding.quantity", "turn_price.price", "cash_account.balance", "lineup.declared_position",
                "season.current_goalkeeper", "season.current_defender",
                "season.current_midfielder", "season.current_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "포메이션 이탈 = 포지션별 |비중 − 현재 포메이션|의 합, 소수 8자리")
        BigDecimal formationDeviation,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수량 1 이상인 보유(종목 번호 오름차순)")
        List<HoldingDto> holdings) {
}
