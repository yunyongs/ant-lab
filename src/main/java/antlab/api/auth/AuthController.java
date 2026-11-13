package antlab.api.auth;

import antlab.api.error.ApiProblem;
import antlab.auth.AccessTokenDto;
import antlab.auth.IssuedTokens;
import antlab.auth.LoginRequest;
import antlab.auth.LoginService;
import antlab.auth.RefreshResult;
import antlab.auth.TokenRotationService;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.MemberDto;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "auth")
public class AuthController {

    private static final String PROBLEM = "application/problem+json";

    private static final String BEARER = "Bearer";

    private final SignupService signups;
    private final LoginService logins;
    private final TokenRotationService rotation;

    public AuthController(SignupService signups, LoginService logins, TokenRotationService rotation) {
        this.signups = signups;
        this.logins = logins;
        this.rotation = rotation;
    }

    @PostMapping(value = "/api/v1/auth/signup", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "signup", summary = "Sign up / 가입",
            description = "Creates a member with the USER role from an email, a display name and a password. Open "
                    + "self-signup without email verification (v0.1). The password is stored only as a BCrypt hash.\n"
                    + "\n"
                    + "이메일·표시 이름·비밀번호로 USER 역할의 회원을 만든다. 이메일 확인 없는 자유 가입이다(v0.1). "
                    + "비밀번호는 BCrypt 해시로만 저장한다.")
    @ApiResponse(responseCode = "201", description = "The new member / 새 회원")
    @ApiResponse(responseCode = "400", description = "The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The email is already registered (in any letter case) — "
            + "EMAIL_TAKEN / 이미 가입한 이메일이다(대소문자 무시) — EMAIL_TAKEN",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public MemberDto signup(@Valid @RequestBody SignupRequest request) {
        return signups.signup(request);
    }

    @PostMapping(value = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "login", summary = "Log in / 로그인",
            description = "Checks the email (in any letter case) and password and starts a login. The access token (JWT, "
                    + "15 minutes) is in the body; keep it in memory only. The refresh token is set as an HttpOnly cookie "
                    + "for /api/v1/auth and is never in a body.\n"
                    + "\n"
                    + "이메일(대소문자 무시)과 비밀번호를 확인하고 로그인을 시작한다. 접근 토큰(JWT, 15분)은 본문에 있으며 "
                    + "메모리에만 둔다. 갱신 토큰은 /api/v1/auth 경로의 HttpOnly 쿠키로 설정되고 본문에는 없다.")
    @ApiResponse(responseCode = "200", description = "Logged in / 로그인함",
            headers = @Header(name = "Set-Cookie", description = "The refresh token cookie (antlab_refresh) / "
                    + "갱신 토큰 쿠키(antlab_refresh)", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "401", description = "The email or password is wrong; the same answer for an unknown "
            + "email — INVALID_CREDENTIALS / 이메일이나 비밀번호가 맞지 않다. 없는 이메일도 같은 답이다 — INVALID_CREDENTIALS",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<AccessTokenDto> login(@Valid @RequestBody LoginRequest request) {
        return issued(logins.login(request.email(), request.password()));
    }

    @PostMapping(value = "/api/v1/auth/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "refresh", summary = "Refresh the access token / 접근 토큰 갱신",
            description = "Rotates the refresh token from the antlab_refresh cookie: the presented token is retired, a "
                    + "new cookie and a new access token are returned. Presenting an already rotated token again ends that "
                    + "login on every device that shares it.\n"
                    + "\n"
                    + "antlab_refresh 쿠키의 갱신 토큰을 회전한다: 제시한 토큰은 폐기하고 새 쿠키와 새 접근 토큰을 돌려준다. "
                    + "이미 회전한 토큰을 다시 제시하면 그 로그인이 모든 기기에서 끝난다.")
    @ApiResponse(responseCode = "200", description = "Refreshed / 갱신함",
            headers = @Header(name = "Set-Cookie", description = "The rotated refresh token cookie (antlab_refresh) / "
                    + "회전한 갱신 토큰 쿠키(antlab_refresh)", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "The refresh token is missing, unknown, expired or revoked — "
            + "UNAUTHENTICATED / 갱신 토큰이 없거나 모르거나 만료·폐기되었다 — UNAUTHENTICATED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<AccessTokenDto> refresh(@Parameter(description = "The refresh token cookie set by login / "
            + "로그인이 설정한 갱신 토큰 쿠키") @CookieValue(name = RefreshCookie.NAME, required = false) String cookie) {
        // The service has committed when it returns, so a reuse revoke survives the 401 below.
        if (rotation.refresh(cookie) instanceof RefreshResult.Issued ok) {
            return issued(ok.tokens());
        }
        throw new DomainException(ErrorCode.UNAUTHENTICATED, "다시 로그인해야 한다");
    }

    @PostMapping(value = "/api/v1/auth/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "logout", summary = "Log out / 로그아웃",
            description = "Ends the login of the antlab_refresh cookie and clears the cookie. Always answers 204, also "
                    + "without a cookie.\n"
                    + "\n"
                    + "antlab_refresh 쿠키의 로그인을 끝내고 쿠키를 지운다. 쿠키가 없어도 언제나 204로 답한다.")
    @ApiResponse(responseCode = "204", description = "Logged out / 로그아웃함",
            headers = @Header(name = "Set-Cookie", description = "Clears the refresh token cookie / 갱신 토큰 쿠키를 지운다",
                    schema = @Schema(type = "string")))
    public ResponseEntity<Void> logout(@Parameter(description = "The refresh token cookie set by login / "
            + "로그인이 설정한 갱신 토큰 쿠키") @CookieValue(name = RefreshCookie.NAME, required = false) String cookie) {
        rotation.logout(cookie);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString())
                .build();
    }

    private static ResponseEntity<AccessTokenDto> issued(IssuedTokens tokens) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.issue(tokens.refreshValue(), tokens.refreshTtl()).toString())
                .body(new AccessTokenDto(tokens.accessToken(), BEARER, tokens.expiresInSeconds()));
    }
}
