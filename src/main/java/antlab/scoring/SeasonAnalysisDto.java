package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Season analysis (MVP-17): the four baselines simulated on the season's own turn prices, the three-way split of the excess over the
 * market and the trading-discipline item. Computed on read from stored, immutable rows and never stored; separate from the 37-column
 * {@link SeasonReportDto}. Money is a whole-won integer string, ratios are strings with 8 decimals, and a value that cannot be
 * computed is null (the key is never omitted). No date, month block, seed, event or asset name: an asset is only {@code MARKET:TICKER}.
 */
@Schema(description = "Season analysis: baselines, three-way split, trading discipline / 시즌 분석: 기준선·3분해·매매 규율")
public record SeasonAnalysisDto(
        @ExposedFrom({"turn_price.price", "turn.total_value"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Version of the analysis rules / 분석 규칙 버전")
        String analysisVersion,
        @ExposedFrom({"asset.market", "asset.ticker"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Version of the representative-asset table / "
                + "대표 자산 표 버전")
        String representativeVersion,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of reported turns = the last snapshot "
                + "turn / 보고 턴 수 = 마지막 스냅샷 턴")
        Integer reportTurns,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The four baselines in a fixed order: MARKET, "
                + "SIXTY_FORTY, FORMATION_FOLLOW, FORMATION_FIXED / 기준선 네 개, 순서 고정")
        List<BaselineDto> baselines,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Three-way split of the excess over the market; null when a formation baseline is "
                        + "unavailable / 시장 대비 초과분의 3분해. 포메이션 기준선이 없으면 null")
        DecompositionDto decomposition,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Trading discipline / 매매 규율")
        DisciplineDto discipline) {

    /** One baseline. When it is unavailable, {@code reason} is set and every value is null (the legs list is empty). */
    @Schema(description = "One baseline portfolio simulated on the season prices / 시즌 가격으로 시뮬레이션한 기준선 하나")
    public record BaselineDto(
            @ExposedFrom({"turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Baseline key (MARKET, SIXTY_FORTY, "
                    + "FORMATION_FOLLOW, FORMATION_FIXED) / 기준선 키")
            String key,
            @ExposedFrom({"turn_price.price", "asset.market", "asset.ticker"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the baseline could be built from the "
                    + "season pool / 시즌 풀로 기준선을 만들 수 있었는지")
            Boolean available,
            @ExposedFrom({"turn_price.price", "asset.market", "asset.ticker"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Why it is unavailable (REPRESENTATIVE_MISSING), else null / 만들 수 없는 이유, 아니면 null")
            String reason,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Slots and legs of the baseline (empty when "
                    + "unavailable) / 기준선의 칸과 구성 종목(없으면 빈 목록)")
            List<LegDto> legs,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Final value of the baseline, whole KRW / 기준선의 최종 평가액, 원 단위 정수")
            BigDecimal finalValue,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Baseline return = (final − initial) ÷ initial, 8 decimal places / 기준선 수익률 = "
                            + "(최종 − 초기) ÷ 초기, 소수 8자리")
            BigDecimal totalReturn,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "season.fee_rate_kr",
                    "season.fee_rate_us", "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Excess of the user's final value over the baseline's, whole KRW / 사용자 최종 평가액이 "
                            + "기준선보다 큰 금액, 원 단위 정수")
            BigDecimal excessWon,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "season.fee_rate_kr",
                    "season.fee_rate_us", "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Excess ÷ initial capital, divided once, 8 decimal places / 초과분 ÷ 초기 자금, 한 번 나눔, "
                            + "소수 8자리")
            BigDecimal excessReturn,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Fees of every fill including the opening purchase, whole KRW / 시작 매수를 포함한 모든 체결의 "
                            + "수수료, 원 단위 정수")
            BigDecimal totalFee,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Turns with a rebalance that traded or was forced by a formation change / 매매가 있었거나 "
                            + "전술 변경으로 강제된 리밸런싱 턴 수")
            Integer rebalanceCount,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Turns where the band was broken but integer shares allowed no trade / 밴드를 넘었지만 "
                            + "정수 주식 때문에 매매가 없던 턴 수")
            Integer bandNoFillCount,
            @ExposedFrom({"turn_price.price", "formation_change.turn_no", "formation_change.after_goalkeeper",
                    "formation_change.after_defender", "formation_change.after_midfielder",
                    "formation_change.after_forward", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Turns with a rebalance forced by a formation change (only the following baseline "
                            + "has any) / 전술 변경으로 강제된 리밸런싱 턴 수(따라가는 기준선만 있다)")
            Integer forcedChangeCount,
            @ExposedFrom({"turn_price.price", "formation_change.turn_no", "formation_change.after_goalkeeper",
                    "formation_change.after_defender", "formation_change.after_midfielder",
                    "formation_change.after_forward", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Fees paid in forced rebalances, whole KRW / 강제 리밸런싱에서 낸 수수료, 원 단위 정수")
            BigDecimal forcedChangeFee,
            @ExposedFrom({"asset.market", "asset.ticker"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the bond leg is not a domestic bond "
                    + "ETF and a US Treasury ETF stands in / 채권 칸이 국내 채권 ETF가 아니라 미국 국채 ETF로 대신된 경우 true")
            Boolean bondLegSubstituted,
            @ExposedFrom({"turn_price.price", "season.initial_cash", "season.fee_rate_kr", "season.fee_rate_us",
                    "season.fx_spread_rate"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Value of the baseline at turns 0..T, before the trades of each turn, whole KRW / 턴 0..T의 "
                            + "기준선 평가액(그 턴의 매매 전), 원 단위 정수")
            List<BigDecimal> turnValues) {
    }

    /** One leg of a baseline slot. */
    @Schema(description = "One asset of a baseline slot / 기준선 칸의 종목 하나")
    public record LegDto(
            @ExposedFrom({"asset.market", "asset.ticker"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset as MARKET:TICKER / 종목, MARKET:TICKER 꼴")
            String asset,
            @ExposedFrom("asset.market")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Slot name (MARKET, EQUITY, BOND, GK, DF, MF, FW) / "
                    + "칸 이름")
            String slot,
            @ExposedFrom("asset.market")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Share of the slot in whole percent / 칸 안의 비중, "
                    + "정수 퍼센트")
            Integer sharePercent) {
    }

    /** Exact split: {@code selection + tactic + structure = total} in won. */
    @Schema(description = "Three-way split of the excess over the market baseline / 시장 기준선 대비 초과분의 3분해")
    public record DecompositionDto(
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Player selection = user final − the following "
                    + "baseline's final, whole KRW / 선수 선택 = 사용자 최종 − 따라가는 기준선 최종, 원 단위 정수")
            BigDecimal selectionWon,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "formation_change.turn_no"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formation change = following baseline's final − "
                    + "fixed baseline's final, whole KRW / 전술 변경 = 따라가는 기준선 최종 − 고정 기준선 최종, 원 단위 정수")
            BigDecimal tacticWon,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Structure = fixed baseline's final − market "
                    + "baseline's final, whole KRW / 구조 = 고정 기준선 최종 − 시장 기준선 최종, 원 단위 정수")
            BigDecimal structureWon,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total = user final − market baseline's final; the "
                    + "three terms add up to it exactly, whole KRW / 합계 = 사용자 최종 − 시장 기준선 최종, 세 항의 합과 "
                    + "정확히 같다, 원 단위 정수")
            BigDecimal totalWon,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Selection ÷ initial capital, 8 decimal places / "
                    + "선수 선택 ÷ 초기 자금, 소수 8자리")
            BigDecimal selectionReturn,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash", "formation_change.turn_no"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formation change ÷ initial capital, 8 decimal "
                    + "places / 전술 변경 ÷ 초기 자금, 소수 8자리")
            BigDecimal tacticReturn,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Structure ÷ initial capital, 8 decimal places / "
                    + "구조 ÷ 초기 자금, 소수 8자리")
            BigDecimal structureReturn,
            @ExposedFrom({"turn.total_value", "turn_price.price", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total ÷ initial capital, divided once; the "
                    + "three ratios may differ from it by rounding, 8 decimal places / 합계 ÷ 초기 자금, 한 번 나눔. 세 "
                    + "비율의 합은 반올림 때문에 다를 수 있다, 소수 8자리")
            BigDecimal totalReturn) {
    }

    /** Panic selling and chase buying of turns 1..T-1 (PLAN §6.2); turn 0 is team building and is never judged. */
    @Schema(description = "Trading discipline: panic selling and chase buying / 매매 규율: 패닉 매도·추격 매수")
    public record DisciplineDto(
            @ExposedFrom("turn_price.price")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "A fall of this ratio or more in the month just "
                    + "shown is a drop, boundary included, 8 decimal places / 방금 본 달에 이 비율 이상 내리면 하락, 경계 "
                    + "포함, 소수 8자리")
            BigDecimal dropThreshold,
            @ExposedFrom("turn_price.price")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "A rise of this ratio or more in the month just "
                    + "shown is a rise, boundary included, 8 decimal places / 방금 본 달에 이 비율 이상 오르면 상승, 경계 "
                    + "포함, 소수 8자리")
            BigDecimal riseThreshold,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "All sells of turns 1..T-1, whole KRW / 턴 1..T-1의 "
                    + "모든 매도 대금, 원 단위 정수")
            BigDecimal sellNotional,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side",
                    "orders.formation_change_id", "turn_price.price", "turn.total_value", "formation_change.turn_no",
                    "formation_change.before_forward", "formation_change.after_forward"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Panic sells, whole KRW / 패닉 매도 대금, 원 단위 정수")
            BigDecimal panicSellNotional,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side",
                    "orders.formation_change_id", "turn_price.price", "turn.total_value", "formation_change.turn_no",
                    "formation_change.before_forward", "formation_change.after_forward"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Panic sells ÷ all sells, 8 decimal places; null when nothing was sold / 패닉 매도 ÷ 전체 "
                            + "매도, 소수 8자리. 판 것이 없으면 null")
            BigDecimal panicSellShare,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "All buys of turns 1..T-1, whole KRW / 턴 1..T-1의 "
                    + "모든 매수 대금, 원 단위 정수")
            BigDecimal buyNotional,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side",
                    "turn_price.price"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Chase buys, whole KRW / 추격 매수 대금, 원 단위 정수")
            BigDecimal chaseBuyNotional,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side",
                    "turn_price.price"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "Chase buys ÷ all buys, 8 decimal places; null when nothing was bought / 추격 매수 ÷ 전체 "
                            + "매수, 소수 8자리. 산 것이 없으면 null")
            BigDecimal chaseBuyShare,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sells plus buys of turns 1..T-1, equal to the "
                    + "report's traded amount, whole KRW / 턴 1..T-1의 매도 + 매수, 성적표의 매매 대금과 같다, 원 단위 정수")
            BigDecimal tradeNotional,
            @ExposedFrom({"execution.fill_price", "execution.quantity", "orders.turn_no", "orders.side",
                    "orders.formation_change_id", "turn_price.price", "turn.total_value", "formation_change.turn_no",
                    "formation_change.before_forward", "formation_change.after_forward"})
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                    description = "(Panic sells + chase buys) ÷ traded amount, 8 decimal places; null when nothing was "
                            + "traded / (패닉 매도 + 추격 매수) ÷ 매매 대금, 소수 8자리. 거래가 없으면 null")
            BigDecimal undisciplinedShare,
            @ExposedFrom({"formation_change.turn_no"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formation change rows made in turns 1..T-1; a turn "
                    + "with two changes counts twice / 턴 1..T-1에 한 전술 변경 행 수. 한 턴에 두 번이면 둘을 센다")
            Integer formationChanges,
            @ExposedFrom({"formation_change.turn_no", "formation_change.before_forward", "formation_change.after_forward",
                    "turn.total_value"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formation change rows that belong to a panic turn "
                    + "(team value fell 8% or more and the forward target ended lower than it started). Counted by row, "
                    + "not by turn / 패닉 턴(팀 평가액이 8% 이상 내렸고 공격 목표가 시작보다 낮게 끝난 턴)에 속한 전술 변경 행 수. "
                    + "턴이 아니라 행 기준")
            Integer panicFormationChanges) {
    }
}
