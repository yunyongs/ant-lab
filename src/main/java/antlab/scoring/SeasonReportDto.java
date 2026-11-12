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
@Schema(description = "Season report / 시즌 성적표")
public record SeasonReportDto(
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season status (FINISHED, because only a "
                + "finished season has a report) / 시즌 상태(끝난 시즌만 성적표를 내므로 FINISHED)")
        SeasonStatus seasonStatus,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the report is final (true for a "
                + "finished season; an interim report during play comes after the MVP)\n"
                + "\n"
                + "확정 여부(끝난 시즌이면 true, 진행 중 잠정 성적표는 MVP 이후)")
        Boolean isFinal,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of reported turns = the last "
                + "snapshot turn / 보고 턴 수 = 마지막 스냅샷 턴")
        Integer reportTurns,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Path length (number of turns) / 경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("orders.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Pending orders of the reported turn (0 for "
                + "a finished season) / 보고 턴의 보류 주문 수(끝난 시즌은 0)")
        Integer pendingOrders,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Pending formation changes of the reported "
                + "turn (0 for a finished season) / 보고 턴의 보류 전술 변경 수(끝난 시즌은 0)")
        Integer pendingChanges,
        @ExposedFrom("season.initial_cash")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Initial capital, whole KRW / 초기 자금, 원 단위 정수")
        BigDecimal initialCash,
        @ExposedFrom("turn.total_value")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Final total valuation, whole KRW / 최종 총 "
                + "평가액, 원 단위 정수")
        BigDecimal finalValue,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total return = (final − initial) ÷ initial, "
                + "8 decimal places / 총 수익률 = (최종 − 초기) ÷ 초기, 소수 8자리")
        BigDecimal totalReturn,
        @ExposedFrom({"turn.total_value", "season.initial_cash"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Monthly volatility = sample standard deviation of monthly returns (n − 1), not "
                        + "annualized, 8 decimal places. null for one turn\n"
                        + "\n"
                        + "월 변동성 = 월 수익률의 표본 표준편차(n − 1), 연율화하지 않음, 소수 8자리. 한 턴이면 null")
        BigDecimal monthlyVolatility,
        @ExposedFrom("turn.drawdown")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Maximum drawdown (0 or less), 8 decimal "
                + "places / 최대 낙폭(0 이하), 소수 8자리")
        BigDecimal maxDrawdown,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Final valuation of the benchmark (buy and "
                + "hold of a proxy ETF for the market index), whole KRW\n"
                + "\n"
                + "기준선(시장 지수 대용 ETF 매수 후 보유)의 최종 평가액, 원 단위 정수")
        BigDecimal baselineFinalValue,
        @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Benchmark return, 8 decimal places / 기준선 "
                + "수익률, 소수 8자리")
        BigDecimal baselineReturn,
        @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "season.fee_rate_kr",
                "season.fee_rate_us", "season.fx_spread_rate"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Excess return = (final − benchmark final) ÷ initial, divided once — not the "
                        + "difference of two returns. 8 decimal places\n"
                        + "\n"
                        + "초과 수익률 = (최종 − 기준선 최종) ÷ 초기, 한 번 나눔 — 두 수익률의 차가 아니다. 소수 8자리")
        BigDecimal excessReturn,
        @ExposedFrom("season.risk_limit")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Risk limit (0 or less), 8 decimal places / "
                + "위험 선언(0 이하), 소수 8자리")
        BigDecimal riskLimit,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of turns where the drawdown crossed "
                + "the risk limit / 낙폭이 위험 선언을 넘은 턴 수")
        Integer riskBreachTurns,
        @ExposedFrom({"turn.drawdown", "season.risk_limit"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Deepest excess beyond the risk limit (0 or "
                + "less, 0 if never crossed), 8 decimal places / 위험 선언을 넘은 가장 깊은 정도(0 이하, 넘은 적 없으면 0), 소수 8자리")
        BigDecimal riskBreachDepth,
        @ExposedFrom({"execution.quantity", "turn_price.price", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Maximum single-asset share = the maximum over turns of (largest asset value ÷ "
                        + "total valuation) (0 without holdings), 8 decimal places\n"
                        + "\n"
                        + "최대 단일 종목 비중 = 턴마다 (가장 큰 종목 가치 ÷ 총 평가액)의 최댓값(보유가 없으면 0), 소수 8자리")
        BigDecimal maxSingleWeight,
        @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total traded amount (fill price × quantity, "
                + "turn-0 team setup excluded), whole KRW / 매매 대금 합(체결가 × 수량, 0턴 팀 구성 제외), 원 단위 정수")
        BigDecimal tradeNotional,
        @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Annualized turnover = 12 × traded amount ÷ "
                + "sum of per-turn total valuations, 8 decimal places\n"
                + "\n"
                + "연 환산 회전율 = 12 × 매매 대금 ÷ 턴별 총 평가액 합, 소수 8자리")
        BigDecimal turnoverAnnual,
        @ExposedFrom({"turn.goalkeeper_value", "turn.defender_value", "turn.midfielder_value", "turn.forward_value",
                "turn.total_value", "formation_change.after_goalkeeper", "formation_change.after_defender",
                "formation_change.after_midfielder", "formation_change.after_forward", "season.start_goalkeeper",
                "season.start_defender", "season.start_midfielder", "season.start_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Average formation deviation = exact average over turns of Σ|actual share − target "
                        + "share| (divided once by the common denominator), 8 decimal places\n"
                        + "\n"
                        + "포메이션 이탈 평균 = 턴마다 Σ|실제 비중 − 목표 비중|의 정확한 평균(공통 분모로 한 번 나눔), 소수 8자리")
        BigDecimal formationDeviation,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Role fit = the exposed value of players whose declared and measured season positions "
                        + "match ÷ the exposed value of measured players, 8 decimal places. null if there is no measured "
                        + "exposure.\n"
                        + "\n"
                        + "역할 수행 = 선언과 시즌 실측 포지션이 같은 선수의 노출 가치 ÷ 실측된 선수의 노출 가치, 소수 8자리. 실측된 노출이 없으면 null")
        BigDecimal roleFit,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of exposed assets whose position was "
                + "measured / 실측 포지션이 나온 노출 종목 수")
        Integer rolePlayersJudged,
        @ExposedFrom({"season_path.month_block_id", "asset_month_return.local_return", "fx_month.fx_change",
                "lineup.declared_position", "execution.quantity", "turn_price.price"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of exposed assets that could not be "
                + "measured for lack of samples / 표본 부족으로 실측하지 못한 노출 종목 수")
        Integer rolePlayersUnjudged,
        @ExposedFrom({"orders.source", "execution.fill_price", "execution.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Encouragement share = traded amount from trades initiated through rankings, feed and alerts ÷ "
                        + "traded amount, 8 decimal places. null if the traded amount is 0.\n"
                        + "\n"
                        + "독려 비중 = 랭킹·피드·알림에서 시작된 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null")
        BigDecimal nudgeShare,
        @ExposedFrom({"orders.source", "turn_feed.turn_no", "orders.turn_no", "execution.fill_price",
                "execution.quantity"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Same-turn encouragement share = encouraged traded amount whose ranking or feed "
                        + "turn equals the order turn ÷ traded amount, 8 decimal places. null if the traded amount "
                        + "is 0\n"
                        + "\n"
                        + "같은 턴 독려 비중 = 랭킹 또는 피드 턴과 주문 턴이 같은 독려 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null")
        BigDecimal nudgeSameTurnShare,
        @ExposedFrom("execution.fee")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total fees (turn-0 team setup included), "
                + "whole KRW / 수수료 합(0턴 팀 구성 포함), 원 단위 정수")
        BigDecimal totalFee,
        @ExposedFrom({"execution.fee", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn-0 team setup fees (buys and sells), "
                + "whole KRW / 0턴 팀 구성 수수료(매수·매도 모두), 원 단위 정수")
        BigDecimal teamFee,
        @ExposedFrom({"execution.fee", "formation_change.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total fees of fills linked to a formation "
                + "change, whole KRW / 전술 변경에 연결된 체결의 수수료 합, 원 단위 정수")
        BigDecimal formationChangeFee,
        @ExposedFrom({"execution.fee", "orders.source"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Encouragement portion of platform revenue = "
                + "fees from trades initiated through rankings and alerts, whole KRW.\n"
                + "\n"
                + "플랫폼 수익 중 독려 몫 = 랭킹·알림에서 시작된 매매의 수수료, 원 단위 정수")
        BigDecimal platformNudgeFee,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of formation changes (in the report "
                + "range) / 전술 변경 수(보고 범위)")
        Integer formationChanges,
        @ExposedFrom({"formation_change.turn_no", "orders.turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of orders linked to a formation "
                + "change / 전술 변경에 연결된 주문 수")
        Integer formationChangeOrders,
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of formation changes with no linked "
                + "order / 연결된 주문이 없는 전술 변경 수")
        Integer changesWithoutOrders,
        @ExposedFrom("reconciliation_log.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of recorded reconciliation mismatch "
                + "rows (recovered attempts included) / 기록된 대사 불일치 행 수(복구된 시도 포함)")
        Integer reconMismatchRows,
        @ExposedFrom({"reconciliation_log.turn_no", "reconciliation_log.attempt_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of turn-advance reconciliation "
                + "bundles (17 rows per turn and attempt) / 턴 전환 대사 묶음 수(턴·시도마다 17행)")
        Integer reconTurnBundles,
        @ExposedFrom({"reconciliation_log.turn_no", "reconciliation_log.attempt_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of manual reconciliation bundles (15 "
                + "rows per turn and attempt) / 수동 대사 묶음 수(턴·시도마다 15행)")
        Integer reconManualBundles,
        @ExposedFrom({"reconciliation_log.turn_no", "season.path_length"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of reconciliation rows for turns beyond the "
                + "path length / 경로 길이를 넘는 턴의 대사 행 수")
        Integer reconOutOfRangeRows) {
}
