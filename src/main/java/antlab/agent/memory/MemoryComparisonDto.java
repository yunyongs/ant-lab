package antlab.agent.memory;

import antlab.common.exposure.ExposedFrom;
import antlab.scoring.SeasonScoreDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Two memory versions of one agent played on the same seeds. Only stored results of closed same-seed groups: the memory
 * version, the valuation multiple, the stored score and the number of failed turns of each side. A group is named by an
 * opaque label; no seed, date, month block or league number is carried, so the report cannot date a path.
 */
public record MemoryComparisonDto(
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Version A as asked; 0 = no memory")
        int versionA,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Version B as asked; 0 = no memory")
        int versionB,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "One row per closed same-seed group, in group creation order")
        List<Group> groups,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Summary summary) {

    /** One closed group in which the agent has a finished season with each version. */
    @Schema(name = "MemoryComparisonGroupDto")
    public record Group(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "G1, G2, ... in group creation order; not a league number")
            String groupLabel,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Side a,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            Side b) {
    }

    /** One side of a group: the season that played one of the two versions. */
    @Schema(name = "MemoryComparisonSideDto")
    public record Side(
            @ExposedFrom("season_agent_config.memory_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"}, description = "Memory version the season was pinned to; null = none")
            Integer memoryVersion,
            @ExposedFrom("season_agent_config.persona_version")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona version the season was pinned to, so a persona change between the two sides is visible")
            Integer personaVersion,
            @ExposedFrom({"turn.total_value", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Final valuation divided by initial capital, 8 decimal places")
            BigDecimal valuationMultiple,
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                    description = "The stored season score with its nine process items; null while no score is stored")
            SeasonScoreDto score,
            @ExposedFrom("agent_call.outcome")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turns whose final call gave no usable decision")
            Integer failedTurns,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the agent met this seed in a season outside the group; "
                    + "should stay false on held-out seeds")
            boolean experiencedSeed) {
    }

    /** Counts and mean differences (B minus A) over the rows; not a statistical claim. */
    @Schema(name = "MemoryComparisonSummaryDto")
    public record Summary(
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED)
            int groups,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Groups where B's valuation multiple is strictly above A's")
            int bWins,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "True under 5 groups: the numbers are anecdotal")
            boolean anecdotal,
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                    description = "Mean of B minus A over the groups that have both values; null without a row")
            MeanDiff meanDiff) {
    }

    /** B minus A, averaged; a value is null when no row has it on both sides. */
    @Schema(name = "MemoryComparisonMeanDiffDto")
    public record MeanDiff(
            @ExposedFrom({"turn.total_value", "season.initial_cash"})
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "8 decimal places")
            BigDecimal valuationMultiple,
            @ExposedFrom("season_score.score")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "2 decimal places")
            BigDecimal score,
            @ExposedFrom("season_score.result_score")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "2 decimal places")
            BigDecimal resultScore,
            @ExposedFrom("season_score.process_score")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "2 decimal places")
            BigDecimal processScore,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Per process item, 2 decimal places")
            SeasonScoreDto.Items items) {
    }
}
