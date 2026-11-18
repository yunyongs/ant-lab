package antlab.season;

import antlab.asset.Position;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Result of a position-only change: the open span after the call. Span number, season number and times are not included. */
@Schema(description = "Position-only change result: the open span after the call. Span number, season number and "
        + "recorded time are not included\n"
        + "\n"
        + "포지션 단독 변경 결과: 호출 뒤의 열린 구간. 구간 번호·시즌 번호·기록 시각은 싣지 않는다")
public record LineupChangeDto(
        @ExposedFrom("lineup.asset_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier / 종목 식별자")
        Long assetId,
        @ExposedFrom("lineup.declared_position")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Declared position of the open span now / "
                + "지금 열린 구간의 선언 포지션")
        Position declaredPosition,
        @ExposedFrom("lineup.start_turn")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The turn the open span started / 열린 구간이 시작한 턴")
        Integer startTurn,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "false when the position was already the "
                + "requested one and no row was written / 이미 그 포지션이라 행을 쓰지 않았으면 false")
        Boolean changed) {

    public static LineupChangeDto from(LineupChangeResult result) {
        Lineup open = result.lineup();
        return new LineupChangeDto(open.getAssetId(), open.getDeclaredPosition(), open.getStartTurn(),
                result.changed());
    }
}
