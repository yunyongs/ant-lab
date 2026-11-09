package antlab.api.error;

import antlab.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * 오류 응답 본문의 명세 전용 모양(RFC 9457 문제 상세 + 최상위 {@code code}). 응답을 만드는 데는 쓰지 않는다 —
 * 실제 본문은 {@link ApiExceptionHandler} 가 ProblemDetail 로 만든다. {@code type} 은 about:blank 라 생략된다.
 */
@Schema(name = "Problem", description = "RFC 9457 문제 상세 + 최상위 code. type은 about:blank라 생략된다")
public record ApiProblem(
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String title,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "int32")
        int status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "고정 한국어 문장. 거부된 값·금액을 싣지 않는다")
        String detail,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String instance,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ErrorCode code,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "@Valid 위반일 때만")
        List<FieldViolation> errors) {

    /** 검증 위반 한 건: 필드 경로와 제약 애너테이션 단순 이름. 거부된 값은 싣지 않는다. */
    public record FieldViolation(
            @Schema(requiredMode = RequiredMode.REQUIRED)
            String field,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "제약 애너테이션 단순 이름(예 Min)")
            String reason) {
    }
}
