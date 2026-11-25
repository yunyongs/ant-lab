package antlab.api.leaderboard;

import antlab.api.error.ApiProblem;
import antlab.leaderboard.LeaderboardKind;
import antlab.leaderboard.LeaderboardService;
import antlab.leaderboard.ManagerLeaderboardDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "leaderboard")
public class LeaderboardController {

    private static final String PROBLEM = "application/problem+json";

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping(value = "/api/v1/leaderboards/manager", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getManagerLeaderboard", summary = "Manager leaderboard / 감독 리더보드",
            description = "Returns the manager leaderboard to any signed-in member. Members are ranked by the career score L "
                    + "only: the trimmed mean of the season scores S of their newest 10 counted seasons minus half their "
                    + "sample standard deviation, rounded to 2 places once; ties share the better rank. A member is listed "
                    + "once they have 3 (human) or 5 (agent) counted seasons; below that they are only counted in unranked, "
                    + "and the viewer sees their own progress in me. The formula is provisional until its status is FINAL, and its "
                    + "version and weights are in the answer. A finished seat of a league that is still open is hidden from "
                    + "S and L (counted as awaitingLeagueClose) until the league closes. The detection result of an agent's "
                    + "model is a report only, and the mean valuation multiple is shown but never ranks; neither filters "
                    + "or reorders rows. kind filters the rows (ALL, HUMAN, AGENT); unranked and me ignore it. No member, "
                    + "season or owner identifier is in the answer. No paging.\n"
                    + "\n"
                    + "로그인한 모든 회원에게 감독 리더보드를 준다. 순위는 경력 점수 L 하나로만 매긴다: 최근 집계 시즌 10개의 시즌 점수 S 를 "
                    + "절사 평균한 값에서 표본 표준편차의 절반을 빼고 소수 2자리에서 한 번만 반올림한 값이며, 동률은 좋은 순위를 함께 받는다. "
                    + "집계 시즌이 3개(사람) 또는 5개(에이전트)가 되어야 표에 오르고, 그 전에는 unranked 에 수로만 들어가며 보는 사람은 "
                    + "me 에서 자신의 진행을 본다. 공식은 상태가 FINAL 이 될 때까지 잠정이며 판과 가중치를 답에 싣는다. 아직 열린 리그의 끝난 자리는 리그가 "
                    + "닫힐 때까지 S 와 L 에서 숨기고 awaitingLeagueClose 로 센다. 에이전트 모델의 탐지 결과는 보고 전용이고 평균 평가 "
                    + "배수는 보이기만 할 뿐 순위에 쓰지 않으며, 둘 다 행을 거르거나 순서를 바꾸지 않는다. kind 는 행을 거른다(ALL, HUMAN, "
                    + "AGENT). unranked 와 me 는 kind 와 무관하다. 회원·시즌·소유자 식별자는 답에 없다. 쪽 나누기 없음.")
    @ApiResponse(responseCode = "200", description = "The manager leaderboard / 감독 리더보드")
    @ApiResponse(responseCode = "400", description = "kind is not ALL, HUMAN or AGENT — INVALID_ARGUMENT / kind 가 ALL·HUMAN·"
            + "AGENT 가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ManagerLeaderboardDto get(@RequestParam(required = false, defaultValue = "ALL") LeaderboardKind kind) {
        return leaderboard.get(kind);
    }
}
