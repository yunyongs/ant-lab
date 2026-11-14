package antlab.api.review;

import antlab.api.error.ApiProblem;
import antlab.common.exposure.FinishedSeasonOnly;
import antlab.common.exposure.ReviewReveal;
import antlab.season.SeasonReviewDto;
import antlab.season.SeasonReviewService;
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
public class SeasonReviewController {

    private static final String PROBLEM = "application/problem+json";

    private final SeasonReviewService reviews;

    public SeasonReviewController(SeasonReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/review", produces = MediaType.APPLICATION_JSON_VALUE)
    @FinishedSeasonOnly
    @ReviewReveal
    @Operation(operationId = "getSeasonReview", summary = "Season review lookup / 시즌 복기 조회",
            description = "Returns which real month each turn of a finished season was (year-month), the "
                    + "one-line summary and event tags of that month (real name, hindsight assessment, source), "
                    + "the formation-change history, and the reproducibility metadata (seed, engine version, "
                    + "data snapshot date). A season that is in progress or being abandoned returns 409 "
                    + "SEASON_NOT_FINISHED and the body carries no year-month or event name. The seed is a "
                    + "64-bit integer, so it is sent as a string. Month block numbers, event numbers and event "
                    + "keys are not included.\n"
                    + "\n"
                    + "Excluded (outside the Phase 0 scope):\n"
                    + "- Regime path and axis direction → M2/MVP\n"
                    + "- Feed reveal (which item was a real event), following attention, defender/bench goals "
                    + "and platform revenue → MVP\n"
                    + "- Event card figures (result, direction, strength, reaction change rate) → MVP\n"
                    + "- Per-asset holdings of past turns and the per-turn valuation list → MVP/WEB\n"
                    + "- Event explanation (RAG) → AI\n"
                    + "\n"
                    + "끝난 시즌의 각 턴이 실제로 어느 달이었는지(연월), 그 달의 한 줄 요약과 사건 태그(실제 이름·사후 평가·출처), 전술 변경 기록, 재현 정보(시드·엔진 "
                    + "버전·데이터 스냅샷 날짜)를 준다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 이고 본문에 연월·사건 이름이 없다. 시드는 "
                    + "64비트 정수라 문자열로 보낸다. 월 블록 번호·사건 번호·사건 키는 싣지 않는다.\n"
                    + "\n"
                    + "제외(Phase 0 범위 밖):\n"
                    + "- 국면 경로·축 방향 → M2/MVP\n"
                    + "- 피드 공개(어느 항목이 실제 사건이었나)·관심 추종·수비수/벤치 득점·플랫폼 수익 → MVP\n"
                    + "- 사건 카드 수치(결과·방향·강도·반응 변화율) → MVP\n"
                    + "- 과거 턴 종목별 보유·턴별 평가액 목록 → MVP/WEB\n"
                    + "- 사건 설명(RAG) → AI")
    @ApiResponse(responseCode = "200", description = "Season review / 시즌 복기")
    @ApiResponse(responseCode = "400", description = "The season number is not a number — INVALID_ARGUMENT / 시즌 "
            + "번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 "
            + "시즌이다 — SEASON_NOT_FINISHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonReviewDto get(@PathVariable Long seasonId) {
        return reviews.get(seasonId);
    }
}
