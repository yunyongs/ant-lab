package antlab.league;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** A league and its seats for the league owner. Seed, base date, engine version and money settings are never in it. */
@Schema(description = "A league and its seats. No seed, date, engine or money setting / 리그와 그 자리들. 시드·날짜·엔진·금액 설정 없음")
public record LeagueTableDto(
        @ExposedFrom("league.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "League identifier / 리그 식별자")
        Long id,
        @ExposedFrom("league.name")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "League name / 리그 이름")
        String name,
        @ExposedFrom("league.status")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        LeagueStatus status,
        @ExposedFrom("league.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Path length (number of turns) / 경로 길이(턴 수)")
        Integer pathLength,
        @ExposedFrom("league.max_entries")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Largest number of seats / 자리 수 상한")
        Integer maxEntries,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seats, oldest season first / 자리, 오래된 시즌 먼저")
        List<LeagueTableEntryDto> entries) {
}
