package antlab.league;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Create a league. The seed, base date and money settings are not taken from the request; the server draws and fixes them. */
public record CreateLeagueRequest(
        @NotBlank @Schema(maxLength = 50) String name,
        Integer pathLength,
        @Min(2) @Max(20) Integer maxEntries) {

    static final int DEFAULT_PATH_LENGTH = 12;
    static final int DEFAULT_MAX_ENTRIES = 8;

    int pathLengthOrDefault() {
        return pathLength == null ? DEFAULT_PATH_LENGTH : pathLength;
    }

    int maxEntriesOrDefault() {
        return maxEntries == null ? DEFAULT_MAX_ENTRIES : maxEntries;
    }
}
