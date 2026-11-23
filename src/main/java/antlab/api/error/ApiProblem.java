package antlab.api.error;

import antlab.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * 오류 응답 본문의 명세 전용 모양(RFC 9457 문제 상세 + 최상위 {@code code}). 응답을 만드는 데는 쓰지 않는다 —
 * 실제 본문은 {@link ApiExceptionHandler} 가 ProblemDetail 로 만든다. {@code type} 은 about:blank 라 생략된다.
 */
@Schema(name = "Problem", description = "RFC 9457 problem details + a top-level code. type is omitted because it "
        + "is about:blank / RFC 9457 문제 상세 + 최상위 code. type은 about:blank라 생략된다")
public record ApiProblem(
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String title,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "int32")
        int status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Fixed Korean sentence. It never carries the "
                + "rejected value or amount / 고정 한국어 문장. 거부된 값·금액을 싣지 않는다")
        String detail,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String instance,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ErrorCode code,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "For @Valid violations, and for an imported "
                + "document that breaks its rules (field is then a JSON pointer and reason the rule keyword) / @Valid 위반일 "
                + "때, 그리고 들여온 문서가 규칙을 어겼을 때(이때 field는 JSON 포인터, reason은 규칙 키워드)")
        List<FieldViolation> errors) {

    /** 검증 위반 한 건: 필드 경로와 제약 애너테이션 단순 이름. 거부된 값은 싣지 않는다. */
    public record FieldViolation(
            @Schema(requiredMode = RequiredMode.REQUIRED)
            String field,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Simple name of the constraint "
                    + "annotation (for example Min), or the rule keyword of an imported document (for example pattern) / "
                    + "제약 애너테이션 단순 이름(예 Min), 또는 들여온 문서의 규칙 키워드(예 pattern)")
            String reason) {
    }
}
