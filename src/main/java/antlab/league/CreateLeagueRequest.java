package antlab.league;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Create a league. The seed, base date and money settings are not taken from the request; the server draws and fixes them,
 * or, with {@code sameSeedAs}, copies them from the root league of an earlier group.
 */
public record CreateLeagueRequest(
        @NotBlank @Schema(maxLength = 50) String name,
        Integer pathLength,
        @Min(2) @Max(20) Integer maxEntries,
        @Min(1) @Schema(description = "Root league of an earlier same-seed group: the new league plays that group's seed "
                + "and settings (a sibling). Must be a root, still OPEN / 먼저 만든 같은 시드 묶음의 뿌리 리그. 새 리그는 그 묶음의 "
                + "시드와 설정을 그대로 쓴다(형제). 뿌리여야 하고 OPEN 이어야 한다") Long sameSeedAs) {

    static final int DEFAULT_PATH_LENGTH = 12;
    static final int DEFAULT_MAX_ENTRIES = 8;

    int pathLengthOrDefault() {
        return pathLength == null ? DEFAULT_PATH_LENGTH : pathLength;
    }

    int maxEntriesOrDefault() {
        return maxEntries == null ? DEFAULT_MAX_ENTRIES : maxEntries;
    }
}
