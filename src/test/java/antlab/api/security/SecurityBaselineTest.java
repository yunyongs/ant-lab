package antlab.api.security;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class SecurityBaselineTest {

    private static final String PROBLEM = "application/problem+json";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    SecurityProblemHandler problems;

    @Test
    void 허용_목록_밖_경로는_자격_없이_401_UNAUTHENTICATED_문제_본문이다() {
        var result = mvc.get().uri("/no/such/path").requestAttr(ANONYMOUS, true).exchange();
        assertThat(result).hasStatus(401).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("인증이 필요하다");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/no/such/path");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"GET,/h2-console/", "GET,/admin", "GET,/api/v1/members", "GET,/api/v1/authx", "GET,/api/v1/auth/me",
            "GET,/API/v1/pool", "GET,/api/v1/pool/", "GET,/api/v1/seasonsX", "HEAD,/no/such/path",
            "OPTIONS,/no/such/path"})
    void 허용_목록_밖_경로_표는_모두_401이다(String method, String path) {
        assertThat(mvc.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).requestAttr(ANONYMOUS, true)).hasStatus(401);
    }

    @Test
    void 처리기_결과가_null이어도_401과_403으로_닫힌다() throws Exception {
        var closed = new SecurityProblemHandler((req, res, handler, ex) -> null);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        closed.commence(new MockHttpServletRequest("GET", "/x"), denied,
                new org.springframework.security.authentication.InsufficientAuthenticationException("x"));
        assertThat(denied.getStatus()).isEqualTo(401);
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        closed.handle(new MockHttpServletRequest("GET", "/x"), forbidden, new AccessDeniedException("x"));
        assertThat(forbidden.getStatus()).isEqualTo(403);
    }

    @Test
    void 시즌_API는_토큰이_없으면_401이고_기본_토큰이면_CSRF_토큰_없이_열린다() {
        assertThat(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(ANONYMOUS, true)).hasStatus(401)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        var pool = mvc.get().uri("/api/v1/pool").exchange();
        assertThat(pool.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void 로그인_화면도_Basic_로그인도_없다() {
        assertThat(mvc.get().uri("/login").requestAttr(ANONYMOUS, true)).hasStatus(401);
        assertThat(mvc.post().uri("/login").requestAttr(ANONYMOUS, true)).hasStatus(401);
        var basic = mvc.get().uri("/no/such/path").header("Authorization", "Basic dXNlcjpwYXNz").exchange();
        assertThat(basic).hasStatus(401);
        assertThat(basic.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void 가입_응답에는_쿠키가_없다() {
        var result = mvc.post().uri("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"cookie-check@example.test\",\"name\":\"cookie\",\"password\":\"correct-horse-battery\"}")
                .exchange();
        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(result.getResponse().getCookies()).isEmpty();
    }

    @Test
    void UserDetailsService_빈이_없다() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void 접근_거부_처리기는_403_FORBIDDEN_문제_본문이다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin-only");
        MockHttpServletResponse response = new MockHttpServletResponse();
        problems.handle(request, response, new AccessDeniedException("x"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(PROBLEM);
        assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"").contains("권한이 없다");
    }
}
