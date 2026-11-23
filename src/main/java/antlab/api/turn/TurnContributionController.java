package antlab.api.turn;

import antlab.api.error.ApiProblem;
import antlab.scoring.TurnContributionDto;
import antlab.scoring.TurnContributionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "turn")
public class TurnContributionController {

    private static final String PROBLEM = "application/problem+json";

    private final TurnContributionService contributions;

    public TurnContributionController(TurnContributionService contributions) {
        this.contributions = contributions;
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/turns/{turnNo}/contribution",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTurnContribution", summary = "Contribution and benchmark of a turn / 턴의 기여와 "
            + "기준 대비",
            description = "Returns, for one played turn, what the price change of each holding and each position added "
                    + "(quantity held through the move × price change), the trading cost paid at the previous turn, and "
                    + "the season report's benchmark up to this turn with the excess return to date. valueChange = "
                    + "holdingsContribution − tradingCost. Only stored facts up to this turn are read, so a turn gives "
                    + "the same answer whenever it is asked, in a season in progress or a finished one; no date or month "
                    + "block is shown.\n"
                    + "\n"
                    + "진행한 턴 하나에 대해 종목별·포지션별 가격 변화가 더한 금액(넘어오는 동안의 보유 수량 × 가격 변화), 직전 턴에 낸 매매 비용, "
                    + "이 턴까지의 성적표 기준선과 누적 초과 수익률을 준다. valueChange = holdingsContribution − tradingCost. 이 턴까지 "
                    + "저장된 사실만 읽으므로 언제 물어도 같은 답이고 진행 중인 시즌과 끝난 시즌이 같다. 날짜·월 블록은 보이지 않는다.")
    @ApiResponse(responseCode = "200", description = "Turn contribution / 턴 기여")
    @ApiResponse(responseCode = "400", description = "turnNo is below 1, or the season or turn number is not a "
            + "number — INVALID_ARGUMENT / turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "That turn has not been played yet — TURN_NOT_REACHED / 아직 진행하지 "
            + "않은 턴이다 — TURN_NOT_REACHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public TurnContributionDto get(@PathVariable Long seasonId, @PathVariable @Min(1) Integer turnNo) {
        return contributions.get(seasonId, turnNo);
    }
}
