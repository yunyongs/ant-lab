package antlab.scoring;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/**
 * The stored score of one finished season. Scores are strings with two decimals (0.00 to 100.00), percentiles strings with
 * eight (0 to 1); a value that does not exist is null and its key is never omitted. It carries no money, month, seed,
 * history digest, engine version or the {@code inputs} record: those stay in the table.
 */
@Schema(description = "Season score / 시즌 점수")
public record SeasonScoreDto(
        @ExposedFrom("season_score.score_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Formula version the score was computed with / "
                + "점수를 계산한 공식 버전")
        String scoreVersion,
        @ExposedFrom({"season_score.score"})
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Season score 0.00-100.00 = 0.5 x "
                + "result + 0.5 x process; null when the season has no path distribution\n\n"
                + "시즌 점수 0.00~100.00 = 0.5 × 결과 + 0.5 × 과정. 경로 분포가 없으면 null")
        BigDecimal score,
        @ExposedFrom("season_score.result_score")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Result score = 100 x the mid-rank "
                + "percentile of the Sharpe difference among the engine paths of the season-start team held still; null "
                + "without a distribution\n\n"
                + "결과 점수 = 100 × (시즌 시작 팀을 그대로 보유한 엔진 경로들 안에서 샤프 차이의 중간 순위 백분위). 분포가 없으면 null")
        BigDecimal resultScore,
        @ExposedFrom("season_score.process_score")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Process score: the equal-weight mean of the item "
                + "scores that have a value, 0.00-100.00 / 과정 점수: 값이 있는 항목 점수의 같은 가중 평균, 0.00~100.00")
        BigDecimal processScore,
        @ExposedFrom("season_score.luck_percentile")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Where the end value of the "
                + "start team, held still, sits among the engine paths, 0-1, 8 decimals; shown, not scored\n\n"
                + "시작 팀을 그대로 보유했을 때의 최종 가치가 엔진 경로 안에서 놓이는 자리, 0~1, 소수 8자리. 보여 주기만 하고 점수에는 넣지 않는다")
        BigDecimal luckPercentile,
        @ExposedFrom("season_score.sharpe_diff_percentile")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Mid-rank percentile of the "
                + "Sharpe difference among the engine paths, 0-1, 8 decimals\n\n"
                + "샤프 차이의 경로 안 중간 순위 백분위, 0~1, 소수 8자리")
        BigDecimal sharpeDiffPercentile,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The nine process item scores; all nine keys are "
                + "always present, null where the input does not exist yet / 과정 항목 점수 아홉 개. 키는 항상 아홉 개이고 "
                + "입력이 아직 없으면 null")
        Items items,
        @ExposedFrom("season_score.process_items")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "How many item scores have a value (1-9) / 값이 있는 "
                + "항목 점수의 수(1~9)")
        Integer processItems,
        @ExposedFrom("season_score.rating_exclusion")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the season counts in the manager rating / "
                + "시즌이 매니저 레이팅에 집계되는지")
        Boolean countedInRating,
        @ExposedFrom("season_score.rating_exclusion")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Why the season is left out of the "
                + "rating: NOT_STARTED (abandoned before turn 3 by a human) or NO_SCORE (no path distribution); null "
                + "when it counts\n\n"
                + "레이팅에서 빠진 이유: NOT_STARTED(사람이 3턴 전에 포기), NO_SCORE(경로 분포 없음). 집계되면 null")
        RatingExclusion ratingExclusion) {

    /** The nine item scores, 0.00-100.00 or null. */
    @Schema(name = "SeasonScoreItemsDto", description = "Process item scores / 과정 항목 점수")
    public record Items(
            @ExposedFrom("season_score.risk_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal risk,
            @ExposedFrom("season_score.discipline_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal discipline,
            @ExposedFrom("season_score.diversification_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal diversification,
            @ExposedFrom("season_score.turnover_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal turnover,
            @ExposedFrom("season_score.consistency_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal consistency,
            @ExposedFrom("season_score.formation_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal formation,
            @ExposedFrom("season_score.role_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal role,
            @ExposedFrom("season_score.noise_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal noise,
            @ExposedFrom("season_score.nudge_score") @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true)
            BigDecimal nudge) {
    }

    public static SeasonScoreDto of(SeasonScore row) {
        Items items = new Items(row.getRiskScore(), row.getDisciplineScore(), row.getDiversificationScore(),
                row.getTurnoverScore(), row.getConsistencyScore(), row.getFormationScore(), row.getRoleScore(),
                row.getNoiseScore(), row.getNudgeScore());
        return new SeasonScoreDto(row.getScoreVersion(), row.getScore(), row.getResultScore(), row.getProcessScore(),
                row.getLuckPercentile(), row.getSharpeDiffPercentile(), items, row.getProcessItems(),
                row.getRatingExclusion() == null, row.getRatingExclusion());
    }
}
