package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 포메이션(포지션별 비중, 정수 퍼센트) — 요청과 응답이 같은 모양이다. 합 100·골키퍼 1 이상. */
@FormationSum
public record FormationDto(
        @ExposedFrom({"season.start_goalkeeper", "season.current_goalkeeper"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "골키퍼 비중(1–100)")
        @NotNull @Min(1) @Max(100)
        Integer goalkeeper,
        @ExposedFrom({"season.start_defender", "season.current_defender"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "수비수 비중(0–100)")
        @NotNull @Min(0) @Max(100)
        Integer defender,
        @ExposedFrom({"season.start_midfielder", "season.current_midfielder"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "미드필더 비중(0–100)")
        @NotNull @Min(0) @Max(100)
        Integer midfielder,
        @ExposedFrom({"season.start_forward", "season.current_forward"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "공격수 비중(0–100)")
        @NotNull @Min(0) @Max(100)
        Integer forward) {

    static FormationDto from(Formation f) {
        return new FormationDto(f.goalkeeper(), f.defender(), f.midfielder(), f.forward());
    }

    /** 검증을 통과한 값만 부른다(엔티티의 포메이션이 두 번째 그물로 다시 검사한다). */
    Formation toFormation() {
        return new Formation(goalkeeper, defender, midfielder, forward);
    }
}
