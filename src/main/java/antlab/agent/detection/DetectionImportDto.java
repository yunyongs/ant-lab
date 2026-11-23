package antlab.agent.detection;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "What a detection summary import did / 탐지 요약 들여오기의 결과")
public record DetectionImportDto(
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the rows were written now; false when the "
                + "same summary was already stored under this source hash and nothing was written / 지금 행을 썼으면 true, "
                + "같은 원본 해시로 같은 요약이 이미 있어 아무것도 쓰지 않았으면 false")
        boolean created,
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "3", description = "Number of windows "
                + "in the summary (one test row each) / 요약의 창 수(창마다 시험 행 하나)")
        int windows) {
}
