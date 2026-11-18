package antlab.season;

import antlab.common.exposure.ExposedFrom;
import antlab.history.AxisDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** One macro axis as the engine labelled the turn's month: a direction word and the size of the axis score. */
@Schema(description = "One macro axis, direction and strength / 거시 축 하나, 방향과 강도")
public record MacroAxisDto(
        @ExposedFrom({"month_block.rate_dir", "month_block.growth_dir", "month_block.fx_dir", "month_block.sentiment_dir"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Direction of the axis: UP, DOWN or NEUTRAL\n\n축의 방향: 상승·하락·중립")
        AxisDirection direction,
        @ExposedFrom({"month_block.rate_strength", "month_block.growth_strength", "month_block.fx_strength",
                "month_block.sentiment_strength"})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Size of the axis score, 0 or more, 8 decimal "
                + "places, no unit\n\n축 점수의 크기, 0 이상, 소수 8자리, 단위 없음")
        BigDecimal strength) {
}
