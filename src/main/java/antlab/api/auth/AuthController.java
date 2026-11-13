package antlab.api.auth;

import antlab.api.error.ApiProblem;
import antlab.member.MemberDto;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "auth")
public class AuthController {

    private static final String PROBLEM = "application/problem+json";

    private final SignupService signups;

    public AuthController(SignupService signups) {
        this.signups = signups;
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
}
