package antlab.member;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * The member's manager rating. The rating row is stored from the first counted season and shown from the third; before
 * that {@code rating} is null and {@code seasonsUntilPublic} says how many counted seasons are missing. Seasons left out
 * of the rating are only counted, never listed. It carries no month, seed or score input.
 */
@Schema(description = "Manager rating / 매니저 레이팅")
public record RatingDto(
        @ExposedFrom("rating_history.pillar")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rating pillar; only MANAGER exists now / "
                + "레이팅 기둥. 지금은 MANAGER 하나")
        RatingPillar pillar,
        @ExposedFrom("rating_history.rating")
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Current rating 0.00-100.00: the "
                + "weighted mean of the last five counted season scores (weights 5, 4, 3, 2, 1 from the newest); null "
                + "until three seasons are counted\n\n"
                + "현재 레이팅 0.00~100.00: 집계된 최근 다섯 시즌 점수의 가중 평균(최근부터 5·4·3·2·1). 세 시즌이 집계되기 전에는 null")
        BigDecimal rating,
        @ExposedFrom("rating_history.seq_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Number of counted seasons / 집계된 시즌 수")
        Integer countedSeasons,
        @ExposedFrom("rating_history.seq_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Counted seasons still missing before the rating is "
                + "shown (0 once shown) / 레이팅이 보이기까지 더 필요한 집계 시즌 수(보이면 0)")
        Integer seasonsUntilPublic,
        @ExposedFrom("season_score.rating_exclusion")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons abandoned before turn 3 by a human, left "
                + "out of the rating / 사람이 3턴 전에 포기해 레이팅에서 뺀 시즌 수")
        Integer notStartedCount,
        @ExposedFrom("season_score.rating_exclusion")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Seasons without a path distribution, left out of the "
                + "rating / 경로 분포가 없어 레이팅에서 뺀 시즌 수")
        Integer noScoreCount,
        @ExposedFrom("season.status")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Finished seasons not scored yet because the engine "
                + "could not answer; they are scored on a later request / 엔진이 답하지 못해 아직 점수가 없는 끝난 시즌 수. "
                + "다음 요청에서 채점한다")
        Integer pendingSeasons,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Counted seasons, newest first, at most 20 / "
                + "집계된 시즌, 최근 것부터 최대 20개")
        List<Entry> history) {

    /** One counted season: its place in the sequence, its score and the rating after it (null before the third). */
    @Schema(name = "RatingEntryDto", description = "Rating history entry / 레이팅 이력 한 줄")
    public record Entry(
            @ExposedFrom("rating_history.seq_no")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Position among the counted seasons, from 1 / "
                    + "집계된 시즌 중 순번(1부터)")
            Integer seqNo,
            @ExposedFrom("season.id")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The season / 시즌 번호")
            Long seasonId,
            @ExposedFrom("season_score.score")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The season score / 시즌 점수")
            BigDecimal score,
            @ExposedFrom("rating_history.rating")
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The rating after this season; "
                    + "null where seqNo is below 3 / 이 시즌 뒤의 레이팅. seqNo 가 3 미만이면 null")
            BigDecimal rating) {
    }
}
