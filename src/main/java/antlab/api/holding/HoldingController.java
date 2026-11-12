package antlab.api.holding;

import antlab.account.HoldingsDto;
import antlab.account.HoldingsQueryService;
import antlab.api.error.ApiProblem;
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
@Tag(name = "holding")
public class HoldingController {

    private static final String PROBLEM = "application/problem+json";

    private final HoldingsQueryService queries;

    public HoldingController(HoldingsQueryService queries) {
        this.queries = queries;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getHoldings", summary = "Current team (holdings) lookup / 현재 팀(보유) 조회",
            description = "Returns the holdings of the current turn × that turn's prices, the total including "
                    + "cash, valuation and share per position, and the difference from the current formation. It "
                    + "is a live value that reflects this turn's trades immediately. The sum of the four shares can differ "
                    + "from 1 by at most 2e-8.\n"
                    + "\n"
                    + "현재 턴의 보유 × 그 턴 가격, 현금 포함 총액, 포지션별 평가액과 비중, 현재 포메이션 대비 차이를 준다. 이번 턴의 매매가 바로 반영되는 실시간 값이다. "
                    + "비중 4개의 합은 1과 최대 2e-8 다를 수 있다.")
    @ApiResponse(responseCode = "200", description = "Current team / 현재 팀")
    @ApiResponse(responseCode = "400", description = "The season number is not a number — INVALID_ARGUMENT / 시즌 "
            + "번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE "
            + "(production, before sign-in) / 현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public HoldingsDto get(@PathVariable Long seasonId) {
        return queries.get(seasonId);
    }
}
