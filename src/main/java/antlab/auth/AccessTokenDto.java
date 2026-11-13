package antlab.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "A short-lived access token\n"
        + "\n"
        + "짧게 쓰는 접근 토큰")
public record AccessTokenDto(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Signed JWT (HS256); send it as a Bearer token / 서명된 JWT(HS256). Bearer 토큰으로 보낸다")
        String accessToken,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Always Bearer / 항상 Bearer")
        String tokenType,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Seconds until the access token expires / 접근 토큰이 만료될 때까지 남은 초")
        long expiresIn) {

    /** Never print the token. */
    @Override
    public String toString() {
        return "AccessTokenDto[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", accessToken=***]";
    }
}
