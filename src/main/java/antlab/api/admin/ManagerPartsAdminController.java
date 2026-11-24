package antlab.api.admin;

import antlab.api.error.ApiProblem;
import antlab.leaderboard.ManagerPartsRunDto;
import antlab.leaderboard.ManagerPartsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator run of the manager parts queue. The URL rule of {@code /api/v1/admin/**} requires the role ADMIN. */
@RestController
@Tag(name = "admin")
public class ManagerPartsAdminController {

    private static final String PROBLEM = "application/problem+json";

    private final ManagerPartsService parts;

    public ManagerPartsAdminController(ManagerPartsService parts) {
        this.parts = parts;
    }

    @PostMapping(value = "/api/v1/admin/manager-parts/run", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "runManagerParts", summary = "Run the manager parts queue / 매니저 부품 대기열 실행",
            description = "Computes and stores the manager parts of up to limit finished seasons that have none yet, "
                    + "earliest finish first, the same work the scheduled job does one season at a time (backfill). "
                    + "Administrators only. Each season is scored first if needed, then sent to the engine; a deterministic "
                    + "refusal is stored as an exclusion, an engine outage stops the run. A stored row is never replaced. "
                    + "The answer is counts only, with no season, member, date or value. It waits for the engine, a few "
                    + "seconds per season.\n"
                    + "\n"
                    + "아직 부품이 없는 끝난 시즌을 끝난 순서대로 limit개까지 계산해 저장한다. 예약 잡이 한 시즌씩 하는 일을 한 번에 "
                    + "한다(소급 채우기). 관리자만 쓴다. 필요하면 시즌을 먼저 채점한 뒤 엔진에 보내며, 결정적 거부는 제외로 "
                    + "저장하고 엔진 장애면 실행을 멈춘다. 저장된 행은 바꾸지 않는다. 답은 건수뿐이고 시즌·회원·날짜·값은 싣지 "
                    + "않는다. 엔진을 기다리므로 시즌마다 몇 초 걸린다.")
    @ApiResponse(responseCode = "200", description = "What the run did / 실행 결과")
    @ApiResponse(responseCode = "400", description = "limit is missing or outside 1..20 — INVALID_ARGUMENT / limit이 없거나 "
            + "1..20 밖이다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not an administrator — FORBIDDEN / 호출자가 관리자가 "
            + "아니다 — FORBIDDEN",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ManagerPartsRunDto run(@RequestParam @Min(1) @Max(ManagerPartsService.MAX_LIMIT) int limit) {
        return ManagerPartsRunDto.of(parts.run(limit, List.of()));
    }
}
