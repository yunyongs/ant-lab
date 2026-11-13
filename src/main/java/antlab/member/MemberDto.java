package antlab.member;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "The member who just signed up. Email, role, password and times are not included\n"
        + "\n"
        + "방금 가입한 회원. 이메일·역할·비밀번호·시각은 싣지 않는다")
public record MemberDto(
        @ExposedFrom("member.id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Member id / 회원 번호")
        Long id,
        @ExposedFrom("member.name")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display name / 표시 이름")
        String name) {
}
