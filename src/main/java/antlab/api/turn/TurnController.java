package antlab.api.turn;

import antlab.api.error.ApiProblem;
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

    public TurnController(TurnFacade turns, TurnQueryService queries) {
        this.turns = turns;
        this.queries = queries;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/turns", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "advanceTurn", summary = "턴 전환(다음 달)",
            description = "다음 달 블록을 적용한다. 한 트랜잭션에서 풀 전체의 다음 턴 가격과 턴 스냅샷을 만들고 대사 17개가 모두 일치하면"
                    + " 기록하고 현재 턴을 올린다. 마지막 턴이면 시즌이 끝난다. 대사가 하나라도 어긋나면 대사 기록만 남기고 409다.")
    @ApiResponse(responseCode = "201", description = "턴 결과")
    @ApiResponse(responseCode = "400", description = "입력이 틀렸다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "지금은 넘길 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, RECONCILIATION_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<TurnResultDto> advance(@PathVariable Long seasonId,
            @Valid @RequestBody AdvanceTurnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(turns.advance(seasonId, request.expectedTurnNo()));
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/turns/{turnNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTurn", summary = "턴 결과 조회",
            description = "한 턴의 결과(평가액·변화·현금·누적 낙폭·위험 선언 대비·포지션별 평가액과 비중·포메이션 차이)를 준다."
                    + " 턴 전환 응답과 같은 형이다. status·pathLength 는 시즌의 지금 값이고, 비중 4개의 합은 1과 최대 2e-8 다를 수 있다."
                    + " 진행 중이든 끝난 시즌이든 같다.")
    @ApiResponse(responseCode = "200", description = "턴 결과")
    @ApiResponse(responseCode = "400", description = "turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "아직 진행하지 않은 턴이다 — TURN_NOT_REACHED(현재 턴보다 큰 번호 전부. 경로 길이를 넘는 번호는 끝난 시즌에서도 이 코드이므로 클라이언트는 pathLength 로 범위를 먼저 거른다)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public TurnResultDto get(@PathVariable Long seasonId, @PathVariable @Min(1) Integer turnNo) {
        return queries.get(seasonId, turnNo);
    }
}
