package antlab.api.turn;

import antlab.api.error.ApiProblem;
import antlab.ranking.RankingBoardDto;
import antlab.ranking.RankingQueryService;
import antlab.season.AdvanceTurnRequest;
import antlab.season.TurnResultDto;
import antlab.season.TurnFacade;
import antlab.season.TurnQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "turn")
public class TurnController {

    private static final String PROBLEM = "application/problem+json";

    private final TurnFacade turns;
    private final TurnQueryService queries;
    private final RankingQueryService rankings;

    public TurnController(TurnFacade turns, TurnQueryService queries, RankingQueryService rankings) {
        this.turns = turns;
        this.queries = queries;
        this.rankings = rankings;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/turns", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "advanceTurn", summary = "Advance the turn (next month) / 턴 전환(다음 달)",
            description = "Applies the next month block. In one transaction it builds the next-turn prices of "
                    + "the whole pool and the turn snapshot; if all 17 reconciliation checks match, it records "
                    + "them and advances the current turn. After the last turn the season ends. If any check "
                    + "mismatches, only the reconciliation log is kept and the answer is 409.\n"
                    + "\n"
                    + "다음 달 블록을 적용한다. 한 트랜잭션에서 풀 전체의 다음 턴 가격과 턴 스냅샷을 만들고 대사 17개가 모두 일치하면 기록하고 현재 턴을 올린다. 마지막 턴이면 "
                    + "시즌이 끝난다. 대사가 하나라도 어긋나면 대사 기록만 남기고 409다.")
    @ApiResponse(responseCode = "201", description = "Turn result / 턴 결과")
    @ApiResponse(responseCode = "400", description = "The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "The turn cannot be advanced now — SEASON_NOT_ACTIVE, SEASON_HALTED (stopped after repeated "
                    + "reconciliation failures; an administrator resumes it), TURN_MISMATCH, RECONCILIATION_FAILED / "
                    + "지금은 넘길 수 없다 — SEASON_NOT_ACTIVE, SEASON_HALTED(대사 실패가 거듭되어 멈춤, 관리자가 재개), "
                    + "TURN_MISMATCH, RECONCILIATION_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<TurnResultDto> advance(@PathVariable Long seasonId,
            @Valid @RequestBody AdvanceTurnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(turns.advance(seasonId, request.expectedTurnNo()));
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/turns/{turnNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTurn", summary = "Turn result lookup / 턴 결과 조회",
            description = "Returns the result of one turn (valuation, change, cash, cumulative drawdown, "
                    + "comparison with the risk limit, valuation and share per position, formation deviation). "
                    + "It has the same shape as the advance-turn response. status and pathLength are the "
                    + "season's values now, and the sum of the four shares can differ from 1 by at most 2e-8. It is the "
                    + "same for a season in progress and a finished one.\n"
                    + "\n"
                    + "한 턴의 결과(평가액·변화·현금·누적 낙폭·위험 선언 대비·포지션별 평가액과 비중·포메이션 차이)를 준다. 턴 전환 응답과 같은 형이다. "
                    + "status·pathLength 는 시즌의 지금 값이고, 비중 4개의 합은 1과 최대 2e-8 다를 수 있다. 진행 중이든 끝난 시즌이든 같다.")
    @ApiResponse(responseCode = "200", description = "Turn result / 턴 결과")
    @ApiResponse(responseCode = "400", description = "turnNo is below 1, or the season or turn number is not a "
            + "number — INVALID_ARGUMENT / turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "That turn has not been played yet — TURN_NOT_REACHED "
            + "(every number above the current turn; a number beyond the path length gets this code even "
            + "in a finished season, so clients first check the range against pathLength)\n"
            + "\n"
            + "아직 진행하지 않은 턴이다 — TURN_NOT_REACHED(현재 턴보다 큰 번호 전부. 경로 길이를 넘는 번호는 끝난 시즌에서도 이 코드이므로 클라이언트는 "
            + "pathLength 로 범위를 먼저 거른다)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public TurnResultDto get(@PathVariable Long seasonId, @PathVariable @Min(1) Integer turnNo) {
        return queries.get(seasonId, turnNo);
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/turns/{turnNo}/ranking", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTurnRanking", summary = "Ranking board of a turn / 턴의 순위판",
            description = "Returns seven lists for one played turn, at most 5 rows each: biggest price rises and falls, "
                    + "most attention (rank and grade only), longest rising and falling streaks (2 turns or more), and "
                    + "the groups with the biggest mean rise and fall. The board is computed from the facts of that turn "
                    + "only, so a turn gives the same answer whenever it is asked, in a season in progress or a finished "
                    + "one.\n"
                    + "\n"
                    + "진행한 턴 하나의 목록 일곱 개를 줄 5개 이하로 준다: 가격이 가장 많이 오르내린 종목, 관심이 가장 큰 종목(순위와 등급만), "
                    + "가장 오래 오르내린 종목(2턴 이상), 평균 상승·하락이 가장 큰 그룹. 그 턴의 자료만으로 계산하므로 언제 물어도 같은 답이고 "
                    + "진행 중인 시즌과 끝난 시즌이 같다.")
    @ApiResponse(responseCode = "200", description = "Ranking board / 순위판")
    @ApiResponse(responseCode = "400", description = "turnNo is below 1, or the season or turn number is not a "
            + "number — INVALID_ARGUMENT / turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "That turn has not been played yet — TURN_NOT_REACHED / 아직 진행하지 "
            + "않은 턴이다 — TURN_NOT_REACHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public RankingBoardDto ranking(@PathVariable Long seasonId, @PathVariable @Min(1) Integer turnNo) {
        return rankings.get(seasonId, turnNo);
    }
}
