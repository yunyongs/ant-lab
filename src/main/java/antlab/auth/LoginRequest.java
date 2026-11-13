package antlab.auth;

import antlab.member.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Email and password\n"
        + "\n"
        + "이메일과 비밀번호")
public record LoginRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Login email, any letter case (at most 255) / 로그인 이메일, 대소문자 무시(최대 255자)")
        @NotBlank @Size(max = 255)
        String email,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "At most 64 characters and 72 UTF-8 bytes / 최대 64자, UTF-8 72바이트")
        @NotBlank @Size(max = 64) @MaxUtf8Bytes(72)
        String password) {

    /** Never print the password. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
