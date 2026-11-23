package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The value line of a season from turn 0 up to the current turn, ascending. Never a future turn. */
@Schema(description = "Value line of a season, turns 0 up to the current turn, ascending. Never a future turn / "
        + "시즌의 가치 곡선, 0턴부터 현재 턴까지 오름차순. 미래 턴은 없다")
public record TurnSummaryListDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<TurnSummaryDto> items) {
}
