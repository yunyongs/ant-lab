package antlab.member;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Email, display name and password. The role is never taken from the request.\n"
        + "\n"
        + "이메일·표시 이름·비밀번호. 역할은 요청에서 받지 않는다")
public record SignupRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Login email, unique in any letter case (at most 255) / 로그인 이메일, 대소문자 무시 유일(최대 255자)")
        @NotBlank @Email @Size(max = 255)
        String email,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Display name (1–50) / 표시 이름(1–50자)")
        @NotBlank @Size(max = 50)
        String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "12–64 characters and at most 72 UTF-8 bytes; no composition rules / 12–64자, UTF-8 72바이트 이하; 문자 조합 규칙 없음")
        @NotBlank @Size(min = 12, max = 64) @MaxUtf8Bytes(72)
        String password) {

    /** Never print the password. */
    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", name=" + name + ", password=***]";
    }
}
