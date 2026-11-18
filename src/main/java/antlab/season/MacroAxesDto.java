package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** The four macro axes of the turn's month. An axis the engine did not evaluate is null (no data, not neutral). */
@Schema(description = "The four macro axes; an axis the engine could not evaluate is null\n\n"
        + "거시 축 네 개. 엔진이 평가하지 못한 축은 null")
public record MacroAxesDto(
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Rate axis; UP means rates are rising / 금리 축. 상승은 금리가 오르는 중")
        MacroAxisDto rate,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Growth axis; UP means growth is improving / 성장 축. 상승은 성장이 좋아지는 중")
        MacroAxisDto growth,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Exchange-rate axis; UP means the dollar is strong against the won / 환율 축. 상승은 달러 강세(원화 약세)")
        MacroAxisDto fx,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Market sentiment axis; UP means calmer markets and more risk appetite / 심리 축. 상승은 시장이 차분하고 위험 선호가 큼")
        MacroAxisDto sentiment) {
}
