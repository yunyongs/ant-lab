package antlab.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Transactional
class SignupApiTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String PATH = "/api/v1/auth/signup";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static String body(String email, String name, String password) {
        return "{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"password\":\"" + password + "\"}";
    }

    private MockMvcTester.MockMvcRequestBuilder post(String json) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private long members() {
        return jdbc.queryForObject("select count(*) from member", Long.class);
    }

    private JsonNode json(String content) {
        return JsonMapper.shared().readTree(content);
    }

    private void assertRejected(String requestJson, int status, String code) throws Exception {
        long before = members();
        var result = post(requestJson).exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
        assertThat(members()).isEqualTo(before);
    }

    private List<Map<String, String>> errors(String requestJson) throws Exception {
        String content = post(requestJson).exchange().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json(content).get("errors").valueStream()
                .map(n -> Map.of("field", n.get("field").asString(), "reason", n.get("reason").asString())).toList();
    }

    @Test
    void 가입하면_201_그리고_id와_name만_돌려준다() throws Exception {
        var result = post(body("player@example.test", "player", PASSWORD)).exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode node = json(result.getResponse().getContentAsString());
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("id", "name");
        assertThat(node.get("name").asString()).isEqualTo("player");
        Map<String, Object> row = jdbc.queryForMap("select role, password from member where email = ?",
                "player@example.test");
        String stored = (String) row.get("password");
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(stored).startsWith("{bcrypt}$2").hasSizeLessThanOrEqualTo(255).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void 요청의_role_필드로_ADMIN이_될_수_없다() throws Exception {
        var result = post("{\"email\":\"mass@example.test\",\"name\":\"mass\",\"password\":\"" + PASSWORD
                + "\",\"role\":\"ADMIN\"}").exchange();
        // Measured: Jackson ignores the unknown field, so the request still succeeds.
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForList("select role from member where email = ?", String.class, "mass@example.test"))
                .containsExactly("USER");
    }

    @Test
    void 같은_이메일을_두_번_가입하면_409_EMAIL_TAKEN이고_이메일은_본문에_없다() throws Exception {
        post(body("dup@example.test", "one", PASSWORD)).exchange();
        long before = members();
        var result = post(body("dup@example.test", "two", PASSWORD)).exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_TAKEN");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("dup@example.test");
        assertThat(members()).isEqualTo(before);
    }

    @Test
    void 대소문자만_다른_이메일도_409() throws Exception {
        post(body("player@example.test", "one", PASSWORD)).exchange();
        assertRejected(body("Player@Example.TEST", "two", PASSWORD), 409, "EMAIL_TAKEN");
    }

    @Test
    void 비밀번호_11자는_400_Size() throws Exception {
        assertRejected(body("a@example.test", "a", "x".repeat(11)), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", "a", "x".repeat(11)))).contains(Map.of("field", "password", "reason", "Size"));
    }

    @Test
    void 비밀번호_65자는_400_Size() throws Exception {
        assertRejected(body("a@example.test", "a", "x".repeat(65)), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", "a", "x".repeat(65)))).contains(Map.of("field", "password", "reason", "Size"));
    }

    @Test
    void 한글_25자_75바이트는_400_MaxUtf8Bytes() throws Exception {
        String hangul = "가".repeat(25);
        assertRejected(body("a@example.test", "a", hangul), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", "a", hangul)))
                .contains(Map.of("field", "password", "reason", "MaxUtf8Bytes"));
    }

    @Test
    void 공백_12자_비밀번호는_400_NotBlank() throws Exception {
        assertRejected(body("a@example.test", "a", " ".repeat(12)), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", "a", " ".repeat(12))))
                .contains(Map.of("field", "password", "reason", "NotBlank"));
    }

    @Test
    void 잘못된_이메일은_400_Email_256자는_400_Size() throws Exception {
        assertRejected(body("not-an-email", "a", PASSWORD), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("not-an-email", "a", PASSWORD))).contains(Map.of("field", "email", "reason", "Email"));
        String longEmail = "a".repeat(256 - "@example.test".length()) + "@example.test";
        assertThat(longEmail).hasSize(256);
        assertRejected(body(longEmail, "a", PASSWORD), 400, "INVALID_ARGUMENT");
        assertThat(errors(body(longEmail, "a", PASSWORD))).contains(Map.of("field", "email", "reason", "Size"));
    }

    @Test
    void 이름이_공백이거나_51자면_400() throws Exception {
        assertRejected(body("a@example.test", " ", PASSWORD), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", " ", PASSWORD))).contains(Map.of("field", "name", "reason", "NotBlank"));
        assertRejected(body("a@example.test", "n".repeat(51), PASSWORD), 400, "INVALID_ARGUMENT");
        assertThat(errors(body("a@example.test", "n".repeat(51), PASSWORD))).contains(Map.of("field", "name", "reason", "Size"));
    }

    @Test
    void 빈_객체는_세_필드_모두_오류() throws Exception {
        assertRejected("{}", 400, "INVALID_ARGUMENT");
        assertThat(errors("{}")).extracting(e -> e.get("field")).contains("email", "name", "password");
    }

    @Test
    void 깨진_JSON은_400_text_plain은_415() throws Exception {
        assertRejected("{깨진", 400, "INVALID_ARGUMENT");
        long before = members();
        var result = mvc.post().uri(PATH).contentType(MediaType.TEXT_PLAIN).content("x").exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertThat(members()).isEqualTo(before);
    }
}
