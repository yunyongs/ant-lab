package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Abandon request. The turn check guards the irreversible mark; a repeat of an abandon in progress ignores it. */
@Schema(description = "The current turn the member saw is checked only while the season is in progress\n"
        + "\n"
        + "회원이 본 현재 턴은 시즌이 진행 중일 때만 확인한다")
public record AbandonSeasonRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The current turn the member saw. 409 "
                + "TURN_MISMATCH if the season is in progress at another turn; ignored when the abandon was already "
                + "marked (repeat) / 회원이 본 현재 턴. 진행 중인 시즌의 현재 턴과 다르면 409 TURN_MISMATCH, 이미 포기가 표시된 "
                + "시즌의 반복 요청에서는 무시한다")
        @NotNull @Min(0) Integer expectedTurnNo) {
}
