package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** 한 턴의 풀 전체 가격(종목 번호 오름차순). */
@Schema(description = "Prices of the whole pool for one turn (ascending asset number) / 한 턴의 풀 전체 가격(종목 번호 오름차순)")
public record TurnPricesDto(
        @ExposedFrom("turn_price.turn_no")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Integer turnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<TurnPriceDto> prices) {
}
