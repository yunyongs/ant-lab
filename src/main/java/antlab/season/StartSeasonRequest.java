package antlab.season;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 시즌 시작 요청. 초기 자금·요율·시드 같은 설정은 요청에서 받지 않는다(서버 설정이 이긴다).
 * 경로 길이는 선택이며 생략하면 12다. 12·24·60 판정은 경로 출처가 한다(여기서 목록으로 막지 않는다).
 */
@Schema(description = "초기 자금·요율·시드는 요청에서 받지 않는다(서버 설정). pathLength 의 12·24·60 판정은 서버 규칙이다")
public record StartSeasonRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "위험 선언(감수할 최대 낙폭): -0.10, -0.20, -0.30, -0.40 중 하나")
        @NotNull @Digits(integer = 1, fraction = 8) @AllowedRiskLimit
        BigDecimal riskLimit,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        @NotNull @Valid
        FormationDto formation,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, defaultValue = "12",
                description = "경로 길이(턴 수) 12, 24, 60. 생략하면 12")
        Integer pathLength) {

    static final int DEFAULT_PATH_LENGTH = 12;

    int pathLengthOrDefault() {
        return pathLength == null ? DEFAULT_PATH_LENGTH : pathLength;
    }
}
