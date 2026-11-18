package antlab.season;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** The four regime probabilities of the turn's month, as stored (4 decimal places). */
@Schema(description = "Four regime probabilities; their sum can differ from 1 by at most 0.0002\n\n"
        + "국면 확률 네 개. 합은 1과 최대 0.0002 다를 수 있다")
public record RegimeProbabilitiesDto(
        @ExposedFrom("month_block.prob_p1")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Probability of rates down and growth down, 4 "
                + "decimal places\n\n금리 하락·성장 둔화 확률, 소수 4자리")
        BigDecimal p1,
        @ExposedFrom("month_block.prob_p2")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Probability of rates down and growth up, 4 "
                + "decimal places\n\n금리 하락·성장 확장 확률, 소수 4자리")
        BigDecimal p2,
        @ExposedFrom("month_block.prob_p3")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Probability of rates up and growth up, 4 "
                + "decimal places\n\n금리 상승·성장 확장 확률, 소수 4자리")
        BigDecimal p3,
        @ExposedFrom("month_block.prob_p4")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Probability of rates up and growth down, 4 "
                + "decimal places\n\n금리 상승·성장 둔화 확률, 소수 4자리")
        BigDecimal p4) {
}
