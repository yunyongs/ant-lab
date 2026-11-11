package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 전술 변경 결과. 변경 번호·시즌 번호·기록 시각·비용은 싣지 않는다 — 번호는 주문 연결을 위한 내부 값이다.
 */
public record FormationChangeDto(
        @ExposedFrom("formation_change.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "변경한 턴")
        Integer turnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "변경 전 포메이션(변경 직전의 현재 값)")
        FormationDto before,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "변경 후 포메이션(요청한 값)")
        FormationDto after,
        @ExposedFrom("formation_change.reason")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, description = "사유. 없으면 null")
        String reason) {

    static FormationChangeDto from(FormationChange c) {
        return new FormationChangeDto(c.getTurnNo(), FormationDto.from(c.getBefore()),
                FormationDto.from(c.getAfter()), c.getReason());
    }
}
