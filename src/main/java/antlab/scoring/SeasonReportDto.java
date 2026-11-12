package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import antlab.season.SeasonStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * 시즌 성적표 — SQL 정의 {@code sr_report} 의 37열과 이름·순서가 1:1 이다(snake → camel). 저장하지 않고 조회로 계산한다.
 * 금액은 원 단위 정수 문자열(소수점 없음), 비율은 소수 8자리 문자열(−0.2 = −20%), 개수는 정수다. 값을 만들 수 없는 칸은 null 이다(키를 생략하지 않는다).
 * 날짜·월 블록·사건·시드·종목 이름·변경 번호는 싣지 않는다 — 날짜 공개는 복기의 몫이다. 기준선은 Phase 0 대용 ETF(069500·SPY 50/50)
 * 매수 후 보유이며 가격 수익률과 수정 종가가 섞인 편향이 있다.
 */
public record SeasonReportDto(
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "시즌 상태(끝난 시즌만 성적표를 내므로 FINISHED)")
        SeasonStatus seasonStatus,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "확정 여부(끝난 시즌이면 true, 진행 중 잠정 성적표는 MVP 이후)")
        Boolean isFinal,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보고 턴 수 = 마지막 스냅샷 턴")
        Integer reportTurns,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("orders.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보고 턴의 보류 주문 수(끝난 시즌은 0)")
        Integer pendingOrders,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "보고 턴의 보류 전술 변경 수(끝난 시즌은 0)")
        Integer pendingChanges,
        @ExposedFrom("season.initial_cash")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "초기 자금, 원 단위 정수")
        BigDecimal initialCash,
        @ExposedFrom("turn.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "최종 총 평가액, 원 단위 정수")
        BigDecimal finalValue,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "총 수익률 = (최종 − 초기) ÷ 초기, 소수 8자리")
        BigDecimal totalReturn,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "월 변동성 = 월 수익률의 표본 표준편차(n − 1), 연율화하지 않음, 소수 8자리. 한 턴이면 null")
        BigDecimal monthlyVolatility,
        @ExposedFrom("turn.drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "최대 낙폭(0 이하), 소수 8자리")
        BigDecimal maxDrawdown,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "기준선(시장 지수 대용 ETF 매수 후 보유)의 최종 평가액, 원 단위 정수")
        BigDecimal baselineFinalValue,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "기준선 수익률, 소수 8자리")
        BigDecimal baselineReturn,
        @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "season.fee_rate_kr",
                "season.fee_rate_us", "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "초과 수익률 = (최종 − 기준선 최종) ÷ 초기, 한 번 나눔 — 두 수익률의 차가 아니다. 소수 8자리")
        BigDecimal excessReturn,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "위험 선언(0 이하), 소수 8자리")
        BigDecimal riskLimit,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "낙폭이 위험 선언을 넘은 턴 수")
        Integer riskBreachTurns,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "위험 선언을 넘은 가장 깊은 정도(0 이하, 넘은 적 없으면 0), 소수 8자리")
        BigDecimal riskBreachDepth,
        @ExposedFrom({"execution.quantity", "turn_price.price", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "최대 단일 종목 비중 = 턴마다 (가장 큰 종목 가치 ÷ 총 평가액)의 최댓값(보유가 없으면 0), 소수 8자리")
        BigDecimal maxSingleWeight,
        @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "매매 대금 합(체결가 × 수량, 0턴 팀 구성 제외), 원 단위 정수")
        BigDecimal tradeNotional,
        @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "연 환산 회전율 = 12 × 매매 대금 ÷ 턴별 총 평가액 합, 소수 8자리")
        BigDecimal turnoverAnnual,
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value",
                "turn.total_value", "formation_change.after_goalkeeper", "formation_change.after_defender",
                "formation_change.after_midfielder", "formation_change.after_forward", "season.start_goalkeeper",
                "season.start_defender", "season.start_midfielder", "season.start_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "포메이션 이탈 평균 = 턴마다 Σ|실제 비중 − 목표 비중|의 정확한 평균(공통 분모로 한 번 나눔), 소수 8자리")
        BigDecimal formationDeviation,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "역할 수행 = 선언과 시즌 실측 포지션이 같은 선수의 노출 가치 ÷ 실측된 선수의 노출 가치, 소수 8자리. 실측된 노출이 없으면 null")
        BigDecimal roleFit,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "실측 포지션이 나온 노출 종목 수")
        Integer rolePlayersJudged,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "표본 부족으로 실측하지 못한 노출 종목 수")
        Integer rolePlayersUnjudged,
        @ExposedFrom({"orders.source", "execution.fill_price", "execution.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "독려 비중 = 랭킹·피드·알림에서 시작된 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null")
        BigDecimal nudgeShare,
        @ExposedFrom({"orders.source", "turn_feed.turn_no", "orders.turn_no", "execution.fill_price",
                "execution.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "같은 턴 독려 비중 = 랭킹 또는 피드 턴과 주문 턴이 같은 독려 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null")
        BigDecimal nudgeSameTurnShare,
        @ExposedFrom("execution.fee")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수수료 합(0턴 팀 구성 포함), 원 단위 정수")
        BigDecimal totalFee,
        @ExposedFrom({"execution.fee", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "0턴 팀 구성 수수료(매수·매도 모두), 원 단위 정수")
        BigDecimal teamFee,
        @ExposedFrom({"execution.fee", "formation_change.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "전술 변경에 연결된 체결의 수수료 합, 원 단위 정수")
        BigDecimal formationChangeFee,
        @ExposedFrom({"execution.fee", "orders.source"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "플랫폼 수익 중 독려 몫 = 랭킹·알림에서 시작된 매매의 수수료, 원 단위 정수")
        BigDecimal platformNudgeFee,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "전술 변경 수(보고 범위)")
        Integer formationChanges,
        @ExposedFrom({"formation_change.turn_no", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "전술 변경에 연결된 주문 수")
        Integer formationChangeOrders,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "연결된 주문이 없는 전술 변경 수")
        Integer changesWithoutOrders,
        @ExposedFrom("reconciliation_log.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "기록된 대사 불일치 행 수(복구된 시도 포함)")
        Integer reconMismatchRows,
        @ExposedFrom({"reconciliation_log.turn_no", "reconciliation_log.attempt_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "턴 전환 대사 묶음 수(턴·시도마다 17행)")
        Integer reconTurnBundles,
        @ExposedFrom({"reconciliation_log.turn_no", "reconciliation_log.attempt_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수동 대사 묶음 수(턴·시도마다 15행)")
        Integer reconManualBundles,
        @ExposedFrom({"reconciliation_log.turn_no", "season.path_length"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "경로 길이를 넘는 턴의 대사 행 수")
        Integer reconOutOfRangeRows) {
}
