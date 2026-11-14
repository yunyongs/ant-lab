package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import antlab.support.SeasonAccountCleaner;
import antlab.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole sign-in life cycle over a real port with real tokens and cookies (no {@code TestTokens} on the path under test):
 * sign up, log in, play one 12-turn season, refresh (rotation), the stale-header rule, replay detection, logout and
 * refresh after logout, and a second member who cannot see the first member's season. The steps share state, so they are ordered.
 * The cookie is sent by hand: a {@code Secure} cookie is never resent over plain {@code http://127.0.0.1} by a cookie jar.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthLifecycleE2ETest extends RealPortTest {

    private CapturedOutput capture;
    private ListAppender<ILoggingEvent> logEvents;
    private static final Logger LOG = LoggerFactory.getLogger(AuthLifecycleE2ETest.class);
    private static final String LOG_MARKER = "auth-lifecycle-log-marker";
    private static final String PASSWORD = "lifecycle-pass-7413";
    private static final String EMAIL_1 = "lifecycle-one@example.test";
    private static final String EMAIL_2 = "lifecycle-two@example.test";
    private static final Pattern COOKIE = Pattern.compile("^antlab_refresh=([A-Za-z0-9_-]*);");
    private static final String SEASON_BODY = "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
            + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    private E2eHttp http;
    private long member1;
    private long season;
    /** Every secret the run produced, to look for in the captured log. */
    private final List<String> secrets = new ArrayList<>();

    private String access1;
    private String cookie1;
    private String refreshedAccess;
    private String cookie2;
    private String cookie3;
    private String accessB;
    private String cookieB;
    private String cookieB2;
    private String access2;

    @BeforeAll
    void fixtures(CapturedOutput classOutput) {
        // The class-level capture keeps everything since this point; a method-level one sees only its own method.
        capture = classOutput;
        // The server logs through Logback to the stream it opened before the capture started, so the capture alone
        // misses them; an appender on the root logger reads every event of this JVM.
        logEvents = new ListAppender<>();
        logEvents.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logEvents);
        cleanup();
        for (String script : new String[] {"history_pool", "replay_months", "turn_returns"}) {
            new ResourceDatabasePopulator(new ClassPathResource("fixtures/" + script + ".sql")).execute(dataSource);
        }
        http = new E2eHttp(port);
        secrets.add(PASSWORD);
        secrets.add(env.getProperty("antlab.auth.jwt-secret"));
    }

    @AfterAll
    void detachLogAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logEvents);
    }

    @AfterAll
    void cleanup() {
        List<Long> members = jdbc.queryForList("SELECT id FROM member WHERE lower(email) IN (?, ?)", Long.class, EMAIL_1, EMAIL_2);
        List<Long> seasons = new ArrayList<>();
        for (Long m : members) {
            seasons.addAll(jdbc.queryForList("SELECT id FROM season WHERE member_id = ?", Long.class, m));
        }
        SeasonAccountCleaner.delete(jdbc, seasons, members, List.of(), List.of(), List.of());
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/cleanup_season_start.sql")).execute(dataSource);
    }

    private Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private Reply get(String path, String token) {
        return http.send(HttpMethod.GET, path, null, null, "application/json", bearer(token));
    }

    private Reply post(String path, String body, String token) {
        return http.send(HttpMethod.POST, path, body, "application/json", "application/json", bearer(token));
    }

    private Reply login(String email) {
        return http.post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private Reply refresh(String cookie, Map<String, String> extra) {
        Map<String, String> headers = new java.util.HashMap<>(extra);
        headers.put("Cookie", "antlab_refresh=" + cookie);
        return http.send(HttpMethod.POST, "/api/v1/auth/refresh", null, null, "application/json", headers);
    }

    private Reply logout(String cookie) {
        return http.send(HttpMethod.POST, "/api/v1/auth/logout", null, null, "application/json",
                Map.of("Cookie", "antlab_refresh=" + cookie));
    }

    private String cookieOf(Reply reply) {
        String header = reply.header("Set-Cookie");
        assertThat(header).as("Set-Cookie of " + reply).isNotNull();
        Matcher m = COOKIE.matcher(header);
        assertThat(m.find()).as("cookie shape").isTrue();
        String value = m.group(1);
        secrets.add(value);
        return value;
    }

    private String accessOf(Reply reply) {
        assertThat(reply.header("Cache-Control")).contains("no-store");
        JsonNode body = reply.json();
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        String token = body.get("accessToken").asString();
        secrets.add(token);
        return token;
    }

    private static String jti(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonMapper.shared().readTree(payload).get("jti").asString();
    }

    private static void assertIssuedCookie(Reply reply) {
        assertThat(reply.headerValues("Set-Cookie")).hasSize(1);
        assertThat(reply.header("Set-Cookie")).startsWith("antlab_refresh=");
        assertCookieAttributes(reply, "Max-Age=1209600");
    }

    /** The attribute set is exact (Expires aside, a derived date): a changed Path or a missing flag fails. */
    private static void assertCookieAttributes(Reply reply, String maxAge) {
        List<String> parts = new ArrayList<>(List.of(reply.header("Set-Cookie").split("; ")));
        parts.remove(0);
        parts.removeIf(p -> p.regionMatches(true, 0, "Expires=", 0, 8));
        assertThat(parts).containsExactlyInAnyOrder(maxAge, "Path=/api/v1/auth", "Secure", "HttpOnly", "SameSite=Strict");
    }

    private static void assertUnauthenticated(Reply reply, String code) {
        assertThat(reply.status()).as(reply.toString()).isEqualTo(401);
        assertThat(reply.isProblem()).isTrue();
        assertThat(reply.code()).isEqualTo(code);
        assertThat(reply.header("Set-Cookie")).isNull();
    }

    private long assetId(String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    private void buy(String token, String ticker, int quantity, String position) {
        Reply order = post("/api/v1/seasons/" + season + "/orders", "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": "
                + assetId(ticker) + ", \"quantity\": " + quantity + ", \"declaredPosition\": \"" + position + "\"}", token);
        assertThat(order.status()).as(order.body()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- sign up and log in

    @Test
    @Order(1)
    void 가입하면_201이고_로그인하면_토큰과_쿠키를_준다() {
        LOG.warn(LOG_MARKER);
        Reply signup = http.post("/api/v1/auth/signup", "{\"email\":\"" + EMAIL_1 + "\",\"name\":\"one\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(signup.status()).as(signup.body()).isEqualTo(201);
        assertThat(signup.body().contains(PASSWORD)).as("the body carries a secret of %d characters", PASSWORD.length()).isFalse();
        member1 = signup.json().get("id").asLong();

        Reply login = login(EMAIL_1);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        access1 = accessOf(login);
        cookie1 = cookieOf(login);
        assertIssuedCookie(login);
        assertThat(cookie1).hasSize(43);
        assertThat(login.body().contains(cookie1)).as("the body carries a secret of %d characters", cookie1.length()).isFalse();
        assertThat(login.json().propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
    }

    @Test
    @Order(2)
    void 가입과_로그인의_거절은_본문만_다르지_않다() {
        Reply wrong = http.post("/api/v1/auth/login", "{\"email\":\"" + EMAIL_1 + "\",\"password\":\"not-the-password-1\"}");
        Reply unknown = http.post("/api/v1/auth/login", "{\"email\":\"nobody-lifecycle@example.test\",\"password\":\"not-the-password-1\"}");
        assertUnauthenticated(wrong, "INVALID_CREDENTIALS");
        assertUnauthenticated(unknown, "INVALID_CREDENTIALS");
        assertThat(wrong.json().get("detail")).isEqualTo(unknown.json().get("detail"));

        Reply taken = http.post("/api/v1/auth/signup",
                "{\"email\":\"" + EMAIL_1.toUpperCase() + "\",\"name\":\"dup\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("EMAIL_TAKEN");

        Reply anonymous = http.get("/api/v1/seasons");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(anonymous.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertUnauthenticated(http.send(HttpMethod.POST, "/api/v1/auth/refresh", null, null, "application/json", Map.of()),
                "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- one season with the login's token

    @Test
    @Order(3)
    void 로그인한_회원이_한_시즌을_끝까지_한다() {
        Reply start = post("/api/v1/seasons", SEASON_BODY, access1);
        assertThat(start.status()).as(start.body()).isEqualTo(201);
        season = start.json().get("id").asLong();
        buy(access1, "000660", 10, "FORWARD_BREAKOUT");
        buy(access1, "069500", 100, "MIDFIELDER");
        buy(access1, "SPY", 5, "DEFENDER");
        buy(access1, "BRK.B", 5, "GOALKEEPER");
        for (int turn = 0; turn < 12; turn++) {
            Reply advance = post("/api/v1/seasons/" + season + "/turns", "{\"expectedTurnNo\": " + turn + "}", access1);
            assertThat(advance.status()).as("turn " + turn + ": " + advance.body()).isEqualTo(201);
        }
        Reply read = get("/api/v1/seasons/" + season, access1);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("status").asString()).isEqualTo("FINISHED");
        Reply report = get("/api/v1/seasons/" + season + "/report", access1);
        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season)).isEqualTo(member1);
    }

    // ---------------------------------------------------------------- refresh

    @Test
    @Order(4)
    void 갱신은_새_접근_토큰과_돌려_쓴_쿠키를_주고_새_토큰으로_시즌을_읽는다() {
        Reply refreshed = refresh(cookie1, Map.of());
        assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
        assertIssuedCookie(refreshed);
        refreshedAccess = accessOf(refreshed);
        cookie2 = cookieOf(refreshed);
        assertThat(cookie2).isNotEqualTo(cookie1);
        assertThat(refreshedAccess).isNotEqualTo(access1);
        assertThat(jti(refreshedAccess)).isNotEqualTo(jti(access1));
        assertThat(refreshed.body().contains(cookie2)).as("the body carries a secret of %d characters", cookie2.length()).isFalse();

        assertThat(get("/api/v1/seasons/" + season, refreshedAccess).status()).isEqualTo(200);
        assertThat(get("/api/v1/seasons", refreshedAccess).json().get("items").size()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void 갱신은_낡은_Authorization_헤더가_있어도_쿠키만_본다() {
        Map<String, Object> claims = tokens.claims(member1, "USER");
        claims.put("exp", TestTokens.ISSUED.getEpochSecond() + 1);
        String stale = tokens.sign(tokens.header(), claims);
        assertThat(get("/api/v1/seasons", stale).status()).as("the stale token is rejected on a season path").isEqualTo(401);

        Reply refreshed = refresh(cookie2, bearer(stale));
        assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
        accessOf(refreshed);
        cookie3 = cookieOf(refreshed);
        assertThat(cookie3).isNotEqualTo(cookie2);
    }

    @Test
    @Order(6)
    void 돌려_쓴_옛_쿠키를_다시_내면_401이고_그_로그인_묶음이_모두_끝난다() {
        assertUnauthenticated(refresh(cookie1, Map.of()), "UNAUTHENTICATED");
        assertUnauthenticated(refresh(cookie3, Map.of()), "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE member_id = ? AND revoked_at IS NULL",
                Long.class, member1)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE member_id = ? AND revoked_reason = 'REUSE_DETECTED'",
                Long.class, member1)).isPositive();
    }

    // ---------------------------------------------------------------- logout

    @Test
    @Order(7)
    void 로그아웃은_204와_지우는_쿠키를_주고_그_쿠키의_갱신은_401이다() {
        Reply login = login(EMAIL_1);
        assertThat(login.status()).isEqualTo(200);
        accessB = accessOf(login);
        cookieB = cookieOf(login);

        Reply rotated = refresh(cookieB, Map.of());
        assertThat(rotated.status()).as("a new login is not touched by the replay of the old one").isEqualTo(200);
        accessOf(rotated);
        cookieB2 = cookieOf(rotated);

        Reply out = logout(cookieB2);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.body()).isEmpty();
        assertThat(out.headerValues("Set-Cookie")).hasSize(1);
        assertThat(out.header("Set-Cookie")).startsWith("antlab_refresh=;");
        assertCookieAttributes(out, "Max-Age=0");

        assertUnauthenticated(refresh(cookieB2, Map.of()), "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE member_id = ? AND revoked_reason = 'LOGOUT'",
                Long.class, member1)).isEqualTo(1);
        assertThat(logout(cookieB2).status()).as("logout answers 204 again").isEqualTo(204);
    }

    @Test
    @Order(8)
    void 로그아웃_전에_받은_접근_토큰은_만료까지_시즌을_읽는다() {
        // Access tokens are stateless: logout revokes only the refresh family (SPRING_JPA §1).
        assertThat(get("/api/v1/seasons/" + season, accessB).status()).isEqualTo(200);
        assertThat(get("/api/v1/seasons/" + season, access1).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- a second member

    @Test
    @Order(9)
    void 둘째_회원은_첫째_회원의_시즌을_볼_수_없다() {
        Reply signup = http.post("/api/v1/auth/signup", "{\"email\":\"" + EMAIL_2 + "\",\"name\":\"two\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(signup.status()).as(signup.body()).isEqualTo(201);
        Reply login = login(EMAIL_2);
        assertThat(login.status()).isEqualTo(200);
        access2 = accessOf(login);
        cookieOf(login);

        assertThat(get("/api/v1/seasons", access2).json().get("items").size()).isZero();
        Reply other = get("/api/v1/seasons/" + season, access2);
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.code()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/v1/seasons/" + season + "/report", access2).status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- logs

    @Test
    @Order(10)
    void 이_시험의_출력에는_토큰_쿠키_값_비밀번호_서명_키가_없다() {
        assertThat(secrets).hasSizeGreaterThan(8).doesNotContainNull();
        StringBuilder events = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logEvents.list)) {
            events.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                events.append(t.getMessage()).append('\n');
            }
        }
        String all = capture.getAll() + "\n" + events;
        // Positive control: a line logged by an earlier method must be in the capture, or the checks below prove nothing.
        assertThat(all.contains(LOG_MARKER)).as("the output capture sees the log").isTrue();
        for (String secret : secrets) {
            assertThat(all.contains(secret)).as("log leaks a secret of %d characters", secret.length()).isFalse();
        }
        for (Reply reply : http.captured()) {
            for (String secret : secrets) {
                assertThat(reply.path()).as("secrets never travel in a URL").doesNotContain(secret);
            }
        }
    }
}
