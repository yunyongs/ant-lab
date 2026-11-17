package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Answer of the abandon request: where the season stands after this request. No money, no dates. */
@Schema(description = "State of the season after this abandon request; repeat the request while the status is "
        + "ABANDONING\n"
        + "\n"
        + "이번 포기 요청 뒤 시즌의 상태. 상태가 ABANDONING 이면 같은 요청을 반복한다")
public record AbandonResultDto(
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season identifier / 시즌 식별자")
        Long seasonId,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "ABANDONING (more turns remain) or FINISHED / "
                + "ABANDONING(남은 턴이 있다) 또는 FINISHED")
        SeasonStatus status,
        @ExposedFrom("season.current_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Current turn now / 지금의 현재 턴")
        Integer currentTurnNo,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Path length (number of turns) / 경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("season.abandoned_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The turn at which the season was abandoned / "
                + "포기한 턴")
        Integer abandonedTurnNo,
        @ExposedFrom("season.abandoned_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True for a human member who abandoned before "
                + "turn 3 (derived from the abandon turn and the member kind) / 사람 회원이 3턴 전에 포기하면 "
                + "true(포기 턴과 회원 종류에서 계산)")
        Boolean notStarted,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Auto turns this request advanced / 이번 요청이 "
                + "진행한 자동 턴 수")
        Integer advancedTurns,
        @ExposedFrom({"season.path_length", "season.current_turn_no"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns still to run: pathLength - currentTurnNo / "
                + "남은 턴: pathLength - currentTurnNo")
        Integer remainingTurns) {
}
