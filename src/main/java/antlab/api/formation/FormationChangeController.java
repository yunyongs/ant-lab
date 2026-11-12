package antlab.api.formation;

import antlab.api.error.ApiProblem;
import antlab.season.ChangeFormationRequest;
import antlab.season.FormationChangeDto;
import antlab.season.FormationChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "formation")
public class FormationChangeController {

    private static final String PROBLEM = "application/problem+json";

    private final FormationChangeService formationChanges;

    public FormationChangeController(FormationChangeService formationChanges) {
        this.formationChanges = formationChanges;
    }

    @PostMapping(value = "/api/v1/seasons/{seasonId}/formation-changes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeFormation", summary = "Formation change / 전술 변경",
            description = "Changes the current formation to a new value and keeps the change history. It can be "
                    + "changed several times in the same turn and every change is recorded. Orders placed after "
                    + "a change in the same turn are tied to the latest change. It cannot be changed at turn 0 "
                    + "while the team is being built.\n"
                    + "\n"
                    + "현재 포메이션을 새 값으로 바꾸고 변경 이력을 남긴다. 같은 턴에 여러 번 바꿀 수 있고 모두 기록된다. 변경 뒤 같은 턴의 주문은 가장 마지막 변경에 묶인다. "
                    + "팀 구성 중인 0턴에는 바꿀 수 없다.")
    @ApiResponse(responseCode = "201", description = "Change result (turn, before, after, reason) / 변경 "
            + "결과(턴·이전·이후·사유)")
    @ApiResponse(responseCode = "400",
            description = "The input is invalid (sum ≠ 100, goalkeeper 0, out of range, missing required value, "
                    + "reason over 200 characters, same formation as now) — INVALID_ARGUMENT\n"
                    + "\n"
                    + "입력이 틀렸다(합 ≠ 100, 골키퍼 0, 범위 밖, 필수값 누락, 사유 200자 초과, 현재와 같은 포메이션) — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "It cannot be changed now — SEASON_NOT_ACTIVE, TURN_MISMATCH (including turn 0), "
                    + "INVALID_FORMATION, EVENT_TIME_CONFLICT\n"
                    + "\n"
                    + "지금은 바꿀 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH(0턴 포함), INVALID_FORMATION, "
                    + "EVENT_TIME_CONFLICT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE "
            + "(production, before sign-in) / 현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<FormationChangeDto> change(@PathVariable Long seasonId,
            @Valid @RequestBody ChangeFormationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(formationChanges.change(seasonId, request));
    }
}
