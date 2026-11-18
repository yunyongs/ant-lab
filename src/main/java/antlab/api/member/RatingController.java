package antlab.api.member;

import antlab.member.RatingDto;
import antlab.member.RatingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "member")
public class RatingController {

    private final RatingQueryService ratings;

    public RatingController(RatingQueryService ratings) {
        this.ratings = ratings;
    }

    @GetMapping(value = "/api/v1/members/me/rating", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMyRating", summary = "My manager rating / 내 매니저 레이팅",
            description = "Returns the manager rating of the signed-in member: the weighted mean of the last five counted "
                    + "season scores (weights 5, 4, 3, 2, 1 from the newest), seasons ordered by when they finished. "
                    + "The rating is null until three seasons are counted (seasonsUntilPublic says how many are "
                    + "missing). Seasons left out of the rating are only counted: notStartedCount (a human abandoned "
                    + "before turn 3) and noScoreCount (no path distribution). Finished seasons are scored first; if the "
                    + "engine cannot answer, the answer is still 200 and pendingSeasons counts the seasons scored on a "
                    + "later request. history lists the counted seasons newest first, at most 20.\n"
                    + "\n"
                    + "로그인한 회원의 매니저 레이팅을 준다: 집계된 최근 다섯 시즌 점수의 가중 평균(최근부터 5·4·3·2·1)이며 시즌 순서는 끝난 순서다. "
                    + "세 시즌이 집계되기 전에는 레이팅이 null 이다(seasonsUntilPublic 은 모자란 시즌 수). 레이팅에서 빠진 시즌은 횟수만 센다: "
                    + "notStartedCount(사람이 3턴 전에 포기), noScoreCount(경로 분포 없음). 끝난 시즌은 먼저 채점하며, 엔진이 답하지 못해도 200 으로 "
                    + "답하고 pendingSeasons 가 나중 요청에서 채점할 시즌 수를 센다. history 는 집계된 시즌을 최근 것부터 최대 20개 준다.")
    @ApiResponse(responseCode = "200", description = "Manager rating / 매니저 레이팅")
    public RatingDto get() {
        return ratings.getMine();
    }
}
