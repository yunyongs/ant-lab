package antlab.api.report;

import antlab.api.error.ApiProblem;
import antlab.common.exposure.FinishedSeasonOnly;
import antlab.scoring.SeasonAnalysisDto;
import antlab.scoring.SeasonAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "season")
public class SeasonAnalysisController {

    private static final String PROBLEM = "application/problem+json";

    private final SeasonAnalysisService analyses;

    public SeasonAnalysisController(SeasonAnalysisService analyses) {
        this.analyses = analyses;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/report/analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    @FinishedSeasonOnly
    @Operation(operationId = "getSeasonAnalysis", summary = "Season analysis lookup / 시즌 분석 조회",
            description = "Simulates four baselines (market, 60/40, my formation followed, start formation fixed) on the "
                    + "season's own turn prices with integer shares and fees, splits the excess over the market into "
                    + "player selection, formation change and structure, and measures trading discipline (panic selling "
                    + "and chase buying, PLAN §6.2), measures the reaction to noise (the share of what was traded that "
                    + "followed an up or down signal of the same turn's feed and ranking boards, PLAN §6.2), "
                    + "splits the fees by order source (the nudged part is RANKING and ALERT only), and gives three review "
                    + "facts (PLAN §6.2): how buys of top-attention assets and all other buys fared over the next 6 turns, "
                    + "the won gain of defender and goalkeeper holdings in turns where the market fell, and the 6-turn "
                    + "effect of each formation change, all on read and "
                    + "without storing anything. Complements the 37-column season report. A season that is in "
                    + "progress or being abandoned returns 409 SEASON_NOT_FINISHED. "
                    + "Money is a whole-won integer string; ratios are strings with 8 decimal places; the three won "
                    + "terms of the split add up to the total exactly, while their ratios may differ from the total "
                    + "ratio by rounding. A baseline whose representative asset is not in the season pool is "
                    + "unavailable (reason REPRESENTATIVE_MISSING, values null). The 60/40 bond leg uses a US Treasury "
                    + "ETF until the pool holds a Korean bond ETF (bondLegSubstituted). The representative table is a "
                    + "fixed rule, not a position judgement. Dates, month blocks, seeds and asset names are not included; "
                    + "an asset is only MARKET:TICKER.\n"
                    + "\n"
                    + "끝난 시즌의 시즌 가격 위에서 기준선 네 개(시장, 60/40, 내 포메이션 따라감, 시작 포메이션 고정)를 정수 주식과 "
                    + "수수료로 시뮬레이션하고, 시장 대비 초과분을 선수 선택·전술 변경·구조로 나누고, 매매 규율(패닉 매도·추격 "
                    + "매수, PLAN §6.2)과 소음 반응(매매 가운데 같은 턴의 피드·순위판의 상승·하락 신호를 따른 몫, PLAN "
                    + "§6.2)을 재고 수수료를 주문 출발점별로 나누며(독려 몫은 RANKING·ALERT 뿐이다), 복기 사실 셋(PLAN §6.2)도 "
                    + "준다: 관심 상위 종목 매수와 그 밖의 매수의 뒤 6턴 성과, 시장이 내린 턴에 수비수·골키퍼 보유 종목이 낸 원 단위 "
                    + "이익, 전술 변경마다의 6턴 효과. 모두 저장 없이 "
                    + "조회로 계산한다. 37항목 시즌 성적표를 보완한다. 진행 중이거나 포기 "
                    + "중인 시즌은 409 SEASON_NOT_FINISHED 다. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열이고, 3분해의 "
                    + "원 단위 세 항은 합계와 정확히 같지만 비율은 반올림 때문에 합계 비율과 다를 수 있다. 대표 종목이 시즌 풀에 "
                    + "없는 기준선은 만들 수 없다(reason REPRESENTATIVE_MISSING, 값 null). 60/40 의 채권 칸은 풀에 국내 채권 ETF가 "
                    + "들어오기 전까지 미국 국채 ETF를 쓴다(bondLegSubstituted). 대표 종목 표는 고정 규칙이며 포지션 판정이 아니다. "
                    + "날짜·월 블록·시드·종목 이름은 싣지 않고 종목은 MARKET:TICKER 로만 가리킨다.")
    @ApiResponse(responseCode = "200", description = "Season analysis / 시즌 분석")
    @ApiResponse(responseCode = "400", description = "The season number is not a number — INVALID_ARGUMENT / 시즌 "
            + "번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 "
            + "시즌이다 — SEASON_NOT_FINISHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonAnalysisDto get(@PathVariable Long seasonId) {
        return analyses.get(seasonId);
    }
}
