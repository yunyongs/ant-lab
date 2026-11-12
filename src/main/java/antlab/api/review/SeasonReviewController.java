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
    @Operation(operationId = "getSeasonReview", summary = "시즌 복기 조회",
            description = "끝난 시즌의 각 턴이 실제로 어느 달이었는지(연월), 그 달의 한 줄 요약과 사건 태그(실제 이름·사후 평가·출처), 전술 변경 기록, 재현 정보(시드·엔진 버전·데이터 스냅샷 날짜)를 준다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 이고 본문에 연월·사건 이름이 없다. 시드는 64비트 정수라 문자열로 보낸다. 월 블록 번호·사건 번호·사건 키는 싣지 않는다.\n" +
                    "\n" +
                    "제외(Phase 0 범위 밖):\n" +
                    "- 국면 경로·축 방향 → M2/MVP\n" +
                    "- 피드 공개(어느 항목이 실제 사건이었나)·관심 추종·수비수/벤치 득점·플랫폼 수익 → MVP\n" +
                    "- 사건 카드 수치(결과·방향·강도·반응 변화율) → MVP\n" +
                    "- 과거 턴 종목별 보유·턴별 평가액 목록 → MVP/WEB\n" +
                    "- 사건 설명(RAG) → AI")
    @ApiResponse(responseCode = "200", description = "시즌 복기")
    @ApiResponse(responseCode = "400", description = "시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "끝나지 않은 시즌이다 — SEASON_NOT_FINISHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonReviewDto get(@PathVariable Long seasonId) {
        return reviews.get(seasonId);
    }
}
