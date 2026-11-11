package antlab.season;

import antlab.common.json.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 전술 변경 요청. 변경 전 포메이션은 서버가 시즌의 현재 값으로 채우고, 같은 턴 주문과의 연결도 서버가 한다 —
 * 요청에서 변경 번호나 이전 비중을 받지 않는다. 사유는 있는 그대로 저장한다(공백을 지우지 않는다).
 */
public record ChangeFormationRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "변경이 보는 턴. 시즌의 현재 턴과 다르면 409. 0턴(팀 구성 중)에는 전술을 바꿀 수 없어 409")
        @NotNull @Min(0)
        Integer expectedTurnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "새 포메이션. 현재와 같으면 400")
        @NotNull @Valid
        FormationDto formation,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, types = {"string", "null"}, maxLength = 200,
                description = "선택 한 줄 사유(최대 200자, 유니코드 문자 수 기준). 생략과 null 은 같다")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String reason) {
}
