package antlab.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import antlab.auth.AccessTokenDto;
import antlab.auth.LoginRequest;
import antlab.auth.RefreshTokens;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(ScriptedClockConfig.class)
@Transactional
class LoginApiTest {

    private static final String PATH = "/api/v1/auth/login";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String EMAIL = "player@example.test";
    private static final Pattern COOKIE = Pattern.compile("^antlab_refresh=([A-Za-z0-9_-]{43});");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SignupService signups;

    @Autowired
    ScriptedClock clock;

    @Autowired
    org.springframework.core.env.Environment env;

    @MockitoSpyBean
    PasswordEncoder encoder;

    @BeforeEach
    void member() {
        clock.reset();
        signups.signup(new SignupRequest(EMAIL, "player", PASSWORD));
        clearInvocations(encoder);
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private MvcTestResult login(String json) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private JsonNode json(MvcTestResult result) throws Exception {
        return JsonMapper.shared().readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private long tokenRows() {
        return jdbc.queryForObject("select count(*) from refresh_token", Long.class);
    }

    private String cookieValue(MvcTestResult result) {
        Matcher m = COOKIE.matcher(result.getResponse().getHeader("Set-Cookie"));
        assertThat(m.find()).as(result.getResponse().getHeader("Set-Cookie")).isTrue();
        return m.group(1);
    }

    @Test
    void 맞는_자격이면_200_본문_키는_세_개이고_저장은_하지_않는_캐시_헤더다() throws Exception {
        MvcTestResult result = login(body(EMAIL, PASSWORD));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode node = json(result);
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
        assertThat(node.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(node.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(cookieValue(result));
    }

    @Test
    void 쿠키_속성은_계획과_같다() {
        MvcTestResult result = login(body(EMAIL, PASSWORD));
        String raw = result.getResponse().getHeader("Set-Cookie");
        assertThat(raw).matches("^antlab_refresh=[A-Za-z0-9_-]{43}; .*");
        assertThat(raw).contains("Path=/api/v1/auth", "Max-Age=1209600", "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain");
        var cookie = result.getResponse().getCookie("antlab_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(1209600);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void 뿌리_행은_해시만_담고_만료는_시계_한_번_읽은_값_더하기_14일이다() {
        Instant now = Instant.parse("2031-05-06T07:08:09Z");
        clock.script(now);
        MvcTestResult result = login(body(EMAIL, PASSWORD));
        String raw = cookieValue(result);
        Map<String, Object> row = jdbc.queryForMap("select * from refresh_token");
        assertThat(row.get("parent_id")).isNull();
        assertThat(row.get("family_id")).isNotNull();
        assertThat(row.get("revoked_at")).isNull();
        assertThat(row.get("revoked_reason")).isNull();
        assertThat(row.get("token_hash")).isEqualTo(RefreshTokens.hash(raw));
        assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(now.plus(Duration.ofDays(14)));
        assertThat(row.get("member_id")).isEqualTo(jdbc.queryForObject("select id from member where email = ?", Long.class, EMAIL));
        assertThat(row.values().stream().map(String::valueOf)).doesNotContain(raw);
    }

    @Test
    void 이메일은_대소문자를_가리지_않는다() {
        assertThat(login(body("PLAYER@Example.TEST", PASSWORD)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 두_번_로그인하면_뿌리가_둘이고_묶음이_다르다() {
        login(body(EMAIL, PASSWORD));
        login(body(EMAIL, PASSWORD));
        assertThat(jdbc.queryForObject("select count(distinct family_id) from refresh_token where parent_id is null"
                + " and revoked_at is null", Long.class)).isEqualTo(2);
    }

    @Test
    void 접근_토큰의_sub는_회원_번호이고_role은_USER이며_이_키로_검증된다() throws Exception {
        MvcTestResult result = login(body(EMAIL, PASSWORD));
        String token = json(result).get("accessToken").asString();
        byte[] key = java.util.Base64.getDecoder().decode(env.getProperty("antlab.auth.jwt-secret"));
        Jwt jwt = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256)
                .build().decode(token);
        Long id = jdbc.queryForObject("select id from member where email = ?", Long.class, EMAIL);
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(id));
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
    }

    private void assertRejectedLikeWrongPassword(MvcTestResult result, String wrongPasswordBody) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(result).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(wrongPasswordBody);
        verify(encoder, times(1)).matches(any(), any());
    }

    @Test
    void 틀린_비밀번호_없는_이메일_고정_회원_noop_행은_같은_401_본문이고_해시_비교는_한_번이다() throws Exception {
        long before = tokenRows();
        MvcTestResult wrong = login(body(EMAIL, "wrong-password-123"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        String reference = wrong.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(wrong.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(wrong.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(json(wrong).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        verify(encoder, times(1)).matches(any(), any());

        // unknown email
        clearInvocations(encoder);
        assertRejectedLikeWrongPassword(login(body("nobody@example.test", "wrong-password-123")), reference);

        // the fixed member's marker password and a non-bcrypt row never match, not even their own "password"
        jdbc.update("insert into member (email, name, password) values ('fixed@example.test', 'fixed', '!')");
        clearInvocations(encoder);
        assertRejectedLikeWrongPassword(login(body("fixed@example.test", "!")), reference);
        jdbc.update("insert into member (email, name, password) values ('noop@example.test', 'noop', '{noop}pw')");
        clearInvocations(encoder);
        assertRejectedLikeWrongPassword(login(body("noop@example.test", "pw")), reference);

        assertThat(tokenRows()).isEqualTo(before);
    }

    @Test
    void 맞는_비밀번호도_해시_비교는_한_번이다() {
        login(body(EMAIL, PASSWORD));
        verify(encoder, times(1)).matches(any(), any());
    }

    @Test
    void 입력이_틀리면_400_INVALID_ARGUMENT와_errors를_준다() throws Exception {
        List<String> bad = List.of(
                body("", PASSWORD),
                body(EMAIL, ""),
                body(EMAIL, "x".repeat(65)),
                body(EMAIL, "가".repeat(25)),
                "{}");
        for (String json : bad) {
            MvcTestResult result = login(json);
            assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
            JsonNode node = json(result);
            assertThat(node.get("code").asString()).isEqualTo("INVALID_ARGUMENT");
            assertThat(node.get("errors").size()).as(json).isGreaterThan(0);
        }
        assertThat(tokenRows()).isZero();
        verify(encoder, times(0)).matches(any(), any());
    }

    @Test
    void 요청과_응답_객체는_비밀을_출력하지_않는다() {
        assertThat(new LoginRequest(EMAIL, "SECRET-pw-777").toString()).doesNotContain("SECRET-pw-777");
        assertThat(new AccessTokenDto("SECRET-token-777", "Bearer", 900).toString()).doesNotContain("SECRET-token-777");
    }
}
