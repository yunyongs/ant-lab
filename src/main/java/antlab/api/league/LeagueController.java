package antlab.api.league;

import antlab.api.error.ApiProblem;
import antlab.league.LeagueTableDto;
import antlab.league.LeagueTableQuery;
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
@Tag(name = "league")
public class LeagueController {

    private static final String PROBLEM = "application/problem+json";

    private final LeagueTableQuery table;

    public LeagueController(LeagueTableQuery table) {
        this.table = table;
    }

    @GetMapping(value = "/api/v1/leagues/{leagueId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getLeagueTable", summary = "League table / 리그 표",
            description = "Returns a league the signed-in member owns with its seats: the seat's season status and turn, "
                    + "the persona and model of an agent seat, and ownsAgentsHere for a human seat whose owner also has an "
                    + "agent seat here. valuationMultiple (final valuation divided by initial capital, 8 decimal places) "
                    + "and multipleRank are set only once the league is CLOSED, and the multiple only for a FINISHED seat; "
                    + "until then every seat shows only its status and turn. The seed, dates, engine version and path are "
                    + "never in the answer. Another member's league and a missing one both answer 404.\n"
                    + "\n"
                    + "로그인한 회원이 소유한 리그와 그 자리를 준다: 자리의 시즌 상태와 턴, 에이전트 자리의 페르소나와 모델, 소유자의 에이전트도 "
                    + "여기 있는 사람 자리의 ownsAgentsHere. valuationMultiple(최종 평가액 ÷ 초기 자금, 소수 8자리)과 "
                    + "multipleRank 는 리그가 CLOSED 가 된 뒤에만 채우고, 배수는 FINISHED 자리에만 채운다. 그때까지 모든 자리는 상태와 턴만 보인다. 시드·날짜·엔진 버전·경로는 "
                    + "답에 없다. 다른 회원의 리그와 없는 리그는 똑같이 404 다.")
    @ApiResponse(responseCode = "200", description = "The league table / 리그 표")
    @ApiResponse(responseCode = "400", description = "The league number is not a number — INVALID_ARGUMENT / 리그 번호가 숫자가 아니다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The league does not exist or is not the caller's — NOT_FOUND / "
            + "리그가 없거나 호출자의 것이 아니다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public LeagueTableDto get(@PathVariable long leagueId) {
        return table.get(leagueId);
    }
}
