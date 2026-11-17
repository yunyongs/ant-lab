package antlab.api.season;

import antlab.api.error.ApiProblem;
import antlab.projection.ProjectionDto;
import antlab.projection.ProjectionMoment;
import antlab.projection.ProjectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "season")
public class ProjectionController {

    private static final String PROBLEM = "application/problem+json";

    private final ProjectionService projections;

    public ProjectionController(ProjectionService projections) {
        this.projections = projections;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/projection", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSeasonProjection", summary = "Projection of the team / 팀의 예상 분포",
            description = "Shows how the value of the season's team could spread over the coming turns: percentile "
                    + "bands per turn, and for a few horizons the end value, the worst drawdown and the chance of "
                    + "breaching the risk limit. phase CURRENT (default) is computed now for the current team. START "
                    + "is the team after the turn-0 team building and END the final team of a finished season; each is "
                    + "computed once and kept. Won values come from the engine's multiples with one rounding.\n"
                    + "\n"
                    + "시즌 팀의 가치가 앞으로 어떻게 퍼질 수 있는지 보여 준다: 턴별 백분위 구간, 몇 개 기간의 끝 가치·최악의 낙폭·위험 선언을 넘을 확률. "
                    + "phase 가 CURRENT(기본)면 현재 팀을 지금 계산한다. START 는 0턴 팀 구성 뒤의 팀, END 는 끝난 시즌의 마지막 팀이며 "
                    + "각각 한 번 계산해 보관한다. 원화 값은 엔진의 배수에서 한 번만 반올림해 만든다.")
    @ApiResponse(responseCode = "200", description = "Projection / 예상 분포")
    @ApiResponse(responseCode = "400", description = "phase or the season number is wrong — INVALID_ARGUMENT / phase 또는 "
            + "시즌 번호가 틀렸다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "That moment is not available — TURN_NOT_REACHED (START before turn 1), SEASON_NOT_FINISHED "
                    + "(END of a season still running), SEASON_NOT_ACTIVE (CURRENT of a finished season)\n"
                    + "\n"
                    + "그 시점은 볼 수 없다 — TURN_NOT_REACHED(1턴 전의 START), SEASON_NOT_FINISHED(진행 중 시즌의 END), "
                    + "SEASON_NOT_ACTIVE(끝난 시즌의 CURRENT)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "The history cannot carry a projection this long — PATH_TOO_LONG / "
            + "이 길이의 예상 분포를 이력이 받쳐 주지 못한다 — PATH_TOO_LONG",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The engine is busy or not reachable; try again later — "
            + "ENGINE_UNAVAILABLE / 엔진이 바쁘거나 연결되지 않는다. 잠시 뒤 다시 시도한다 — ENGINE_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ProjectionDto get(@PathVariable Long seasonId,
            @RequestParam(required = false, defaultValue = "CURRENT") ProjectionMoment phase) {
        return projections.get(seasonId, phase);
    }
}
