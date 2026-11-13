package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * Review of a finished season. Carries no block number, event id, event key, base date, member id or timestamp.
 * The seed is a decimal string because a JS number loses digits of a 64-bit integer.
 */
@Schema(description = "Season review for a finished season. Month block number, event number, event key, reference date, "
        + "member number and times are not included\n"
        + "\n"
        + "끝난 시즌의 복기. 월 블록 번호·사건 번호·사건 키·기준일·회원 번호·시각은 싣지 않는다")
public record SeasonReviewDto(
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Season status (FINISHED, because only a "
                + "finished season has a season review) / 시즌 상태(끝난 시즌만 복기를 내므로 FINISHED)")
        SeasonStatus seasonStatus,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Path length (number of turns) / 경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("season.abandoned_turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "The turn that was given "
                + "up. null if the season was not given up / 포기한 턴. 포기하지 않았으면 null")
        Integer abandonedTurnNo,
        @ExposedFrom("season.seed")
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?[0-9]{1,19}$", description = "Path seed (decimal "
                + "string of a 64-bit integer) / 경로 시드(64비트 정수의 10진 문자열)")
        String seed,
        @ExposedFrom("season.engine_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Engine version that built the path / 경로를 만든 "
                + "엔진 버전")
        String engineVersion,
        @ExposedFrom("season.data_snapshot_date")
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "Data snapshot date "
                + "(yyyy-MM-dd) / 데이터 스냅샷 날짜(yyyy-MM-dd)")
        String dataSnapshotDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "In turn-number order (1..path length) / 턴 "
                + "번호 순서(1..경로 길이)")
        List<ReviewTurnDto> turns,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formation-change history (in change order). "
                + "An empty list if there is none / 전술 변경 기록(변경 순서). 없으면 빈 목록")
        List<FormationChangeDto> formationChanges) {
}
