package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One review turn: the real year-month used for the turn and that month's events. Carries no block number. */
public record ReviewTurnDto(
        @ExposedFrom("season_path.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn number (from 1) / 턴 번호(1부터)")
        Integer turnNo,
        @ExposedFrom("month_block.month_start")
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{4}-[0-9]{2}$", description = "The real year-month "
                + "used in this turn (yyyy-MM) / 이 턴에 쓰인 실제 연월(yyyy-MM)")
        String yearMonth,
        @ExposedFrom("month_block.event_summary")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "One-line summary of "
                + "that month. null if there is none / 그 달의 한 줄 요약. 없으면 null")
        String eventSummary,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Event tags of that month (in event key "
                + "order). An empty list if there are none / 그 달의 사건 태그(사건 키 순서). 없으면 빈 목록")
        List<ReviewEventDto> events) {
}
