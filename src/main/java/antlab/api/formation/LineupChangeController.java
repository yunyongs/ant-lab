package antlab.api.formation;

import antlab.api.error.ApiProblem;
import antlab.season.ChangeLineupRequest;
import antlab.season.LineupChangeDto;
import antlab.season.LineupChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "formation")
public class LineupChangeController {

    private static final String PROBLEM = "application/problem+json";

    private final LineupChangeService lineupChanges;

    public LineupChangeController(LineupChangeService lineupChanges) {
        this.lineupChanges = lineupChanges;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/lineup-changes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeLineup", summary = "Position-only change / 포지션 단독 변경",
            description = "Changes the declared position of a held asset without any trade: the open span is closed "
                    + "at the current turn and a new span opens, so the next turn values the asset under the new "
                    + "position. No order, fee or formation change is created. If the position is already the "
                    + "requested one the answer is 200 with changed = false and nothing is written. It cannot be "
                    + "changed at turn 0 while the team is being built.\n"
                    + "\n"
                    + "거래 없이 보유 종목의 선언 포지션만 바꾼다. 열린 구간을 현재 턴에 닫고 새 구간을 열어 다음 턴부터 새 포지션으로 평가한다. "
                    + "주문·수수료·전술 변경은 만들지 않는다. 이미 그 포지션이면 changed = false 로 200 을 답하고 아무것도 쓰지 않는다. "
                    + "팀 구성 중인 0턴에는 바꿀 수 없다.")
    @ApiResponse(responseCode = "200", description = "The open span after the call and whether it changed / 호출 뒤의 "
            + "열린 구간과 변경 여부")
    @ApiResponse(responseCode = "400",
            description = "The input is invalid (missing required value, unknown position, asset number below 1) "
                    + "— INVALID_ARGUMENT\n"
                    + "\n"
                    + "입력이 틀렸다(필수값 누락, 알 수 없는 포지션, 종목 번호 1 미만) — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "It cannot be changed now — SEASON_NOT_ACTIVE (not in progress), TURN_MISMATCH (including "
                    + "turn 0), LINEUP_MISMATCH\n"
                    + "\n"
                    + "지금은 바꿀 수 없다 — SEASON_NOT_ACTIVE(진행 중이 아님), TURN_MISMATCH(0턴 포함), LINEUP_MISMATCH",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422",
            description = "The asset is not held (never bought, sold out or not in the pool) — INSUFFICIENT_HOLDING\n"
                    + "\n"
                    + "보유 중인 종목이 아니다(산 적 없음, 전량 매도, 풀에 없음) — INSUFFICIENT_HOLDING",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public LineupChangeDto change(@PathVariable Long seasonId, @Valid @RequestBody ChangeLineupRequest request) {
        return LineupChangeDto.from(lineupChanges.change(seasonId, request.expectedTurnNo(), request.assetId(),
                request.declaredPosition()));
    }
}
