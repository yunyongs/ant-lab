package antlab.api.report;

import antlab.api.error.ApiProblem;
import antlab.scoring.SeasonScoreDto;
import antlab.scoring.SeasonScoreService;
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
public class SeasonScoreController {

    private static final String PROBLEM = "application/problem+json";

    private final SeasonScoreService scores;

    public SeasonScoreController(SeasonScoreService scores) {
        this.scores = scores;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/score", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSeasonScore", summary = "Season score lookup / 시즌 점수 조회",
            description = "Returns the score of a finished season: 0.5 x the result score (the season's Sharpe "
                    + "difference against the market benchmark, ranked among engine paths of the season-start team held "
                    + "still) + 0.5 x the process score (the mean of the item scores that have a value). The score is "
                    + "computed on the first request after the season finished, in finish order together with the "
                    + "member's earlier seasons, and then kept; a later request returns the stored row. The result and "
                    + "the score are null when the history cannot carry a path distribution for the season; such a "
                    + "season, and a human season abandoned before turn 3, is left out of the rating "
                    + "(ratingExclusion). While the engine cannot answer, nothing is stored and the answer is 503 "
                    + "ENGINE_UNAVAILABLE; try again later. The luck percentile is shown and is not part of the score. "
                    + "The response has no money, month, seed or engine detail.\n"
                    + "\n"
                    + "끝난 시즌의 점수를 준다: 0.5 × 결과 점수(시즌의 샤프 차이를 시장 기준선과 비교해, 시즌 시작 팀을 그대로 보유한 엔진 경로들 안에서 "
                    + "순위를 매긴 값) + 0.5 × 과정 점수(값이 있는 항목 점수의 평균). 점수는 시즌이 끝난 뒤 첫 요청에서 회원의 앞선 시즌과 함께 끝난 "
                    + "순서대로 계산해 보관하고, 이후 요청은 보관한 행을 준다. 이력이 시즌의 경로 분포를 받쳐 주지 못하면 결과 점수와 점수는 null 이고, "
                    + "그런 시즌과 3턴 전에 포기한 사람의 시즌은 레이팅에서 빠진다(ratingExclusion). 엔진이 답하지 못하는 동안에는 아무것도 저장하지 "
                    + "않고 503 ENGINE_UNAVAILABLE 로 답하니 나중에 다시 시도한다. 운 백분위는 보여 주기만 하고 점수에 들어가지 않는다. 응답에는 금액·"
                    + "달·시드·엔진 세부가 없다.")
    @ApiResponse(responseCode = "200", description = "Season score / 시즌 점수")
    @ApiResponse(responseCode = "400", description = "The season number is not a number — INVALID_ARGUMENT / 시즌 "
            + "번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist or is another member's — NOT_FOUND / "
            + "시즌이 없거나 다른 회원의 것이다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 "
            + "시즌이다 — SEASON_NOT_FINISHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The score cannot be computed now because the engine is busy or not "
            + "reachable; nothing is stored, try again later — ENGINE_UNAVAILABLE / 엔진이 바쁘거나 연결되지 않아 지금은 "
            + "점수를 계산할 수 없다. 아무것도 저장하지 않았으니 나중에 다시 시도한다 — ENGINE_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonScoreDto get(@PathVariable Long seasonId) {
        return scores.get(seasonId);
    }
}
