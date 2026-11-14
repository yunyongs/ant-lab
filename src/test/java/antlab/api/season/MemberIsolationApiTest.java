package antlab.api.season;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.security.PublicPaths;
import antlab.member.MemberDto;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import antlab.support.TestTokens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two members through the real chain with the fixed member off (the {@code prod} setting): each sees only their own
 * seasons, a stranger gets 404 on every {@code {seasonId}} operation without any write, and a token naming no member
 * is a 401. Rows are committed and removed after each test.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.single-member.enabled=false")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class MemberIsolationApiTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String START_BODY = """
            {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}}""";
    private static final List<String> WRITE_TABLES = List.of("orders", "cash_ledger", "formation_change", "turn");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SignupService signup;
    @Autowired
    TestTokens tokens;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private final List<Long> memberIds = new ArrayList<>();
    private long memberA;
    private long memberB;
    private String emailA;
    private String tokenA;
    private String tokenB;
    private long seasonA;

    @BeforeEach
    void twoMembersAndOneSeason() throws Exception {
        emailA = "iso-a-" + UUID.randomUUID() + "@example.test";
        memberA = signUp(emailA);
        memberB = signUp("iso-b-" + UUID.randomUUID() + "@example.test");
        tokenA = loginToken(emailA);
        tokenB = tokens.user(memberB);

        MockHttpServletResponse started = send(HttpMethod.POST, "/api/v1/seasons", tokenA, START_BODY);
        assertThat(started.getStatus()).isEqualTo(201);
        seasonA = JsonMapper.shared().readTree(started.getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, seasonA)).isEqualTo(memberA);

        MockHttpServletResponse bought = send(HttpMethod.POST, "/api/v1/seasons/" + seasonA + "/orders", tokenA, orderBody());
        assertThat(bought.getStatus()).as(bought.getContentAsString()).isEqualTo(201);
    }

    @AfterEach
    void removeRows() {
        List<Long> seasonIds = jdbc.queryForList("SELECT id FROM season WHERE member_id IN (?, ?)", Long.class,
                memberA, memberB);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    private long signUp(String email) {
        MemberDto member = signup.signup(new SignupRequest(email, "가짜 회원", PASSWORD));
        memberIds.add(member.id());
        return member.id();
    }

    private String loginToken(String email) throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("accessToken").asString();
    }

    private String orderBody() {
        long asset = jdbc.queryForObject("SELECT id FROM asset WHERE ticker = '000660'", Long.class);
        return "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + asset
                + ", \"quantity\": 10, \"declaredPosition\": \"FORWARD_BREAKOUT\"}";
    }

    private MockHttpServletResponse send(HttpMethod method, String uri, String token, String body) {
        var request = mvc.method(method).uri(uri).accept(MediaType.APPLICATION_JSON);
        if (token == null) {
            request = request.requestAttr(ANONYMOUS, true);
        } else {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange().getResponse();
    }

    private record SeasonOperation(String method, String template, boolean hasBody) {
        String path(long seasonId) {
            return template.replace("{seasonId}", String.valueOf(seasonId)).replace("{turnNo}", "1");
        }

        String body(String orderBody) {
            if (!hasBody) {
                return null;
            }
            if (template.endsWith("/orders")) {
                return orderBody;
            }
            if (template.endsWith("/formation-changes")) {
                return "{\"expectedTurnNo\": 1, \"formation\": {\"goalkeeper\": 10, \"defender\": 20,"
                        + " \"midfielder\": 30, \"forward\": 40}}";
            }
            return "{\"expectedTurnNo\": 0}";
        }
    }

    /** Every contract operation on a single season, read from the handler mapping (never typed here). */
    private List<SeasonOperation> seasonOperations() {
        List<SeasonOperation> out = new ArrayList<>();
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            boolean hasBody = Arrays.stream(entry.getValue().getMethodParameters())
                    .anyMatch(p -> p.hasParameterAnnotation(RequestBody.class));
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/dev/")
                        || PublicPaths.CONTRACT_PUBLIC.contains(pattern) || !pattern.contains("{seasonId}")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    out.add(new SeasonOperation(method.name(), pattern, hasBody));
                }
            }
        }
        out.sort((a, b) -> (a.method() + a.template()).compareTo(b.method() + b.template()));
        return out;
    }

    private Map<String, Long> writeCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : WRITE_TABLES) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        out.put("cash_balance_sum", jdbc.queryForObject("SELECT coalesce(sum(balance), 0) FROM cash_account", Long.class));
        out.put("current_turn_sum", jdbc.queryForObject("SELECT coalesce(sum(current_turn_no), 0) FROM season", Long.class));
        return out;
    }

    @Test
    void 목록은_자기_시즌만_보인다() throws Exception {
        JsonNode mine = JsonMapper.shared().readTree(send(HttpMethod.GET, "/api/v1/seasons", tokenA, null).getContentAsString());
        JsonNode theirs = JsonMapper.shared().readTree(send(HttpMethod.GET, "/api/v1/seasons", tokenB, null).getContentAsString());
        assertThat(mine.get("items")).hasSize(1);
        assertThat(mine.get("items").get(0).get("id").asLong()).isEqualTo(seasonA);
        assertThat(theirs.get("items")).isEmpty();
    }

    @Test
    void 남의_시즌은_모든_시즌_연산에서_404이고_아무것도_쓰지_않는다() throws Exception {
        List<SeasonOperation> operations = seasonOperations();
        assertThat(operations).as("vacuity guard: season operations found").hasSizeGreaterThanOrEqualTo(9);
        Map<String, Long> before = writeCounts();

        for (String stranger : List.of(tokenB, tokens.admin(memberB))) {
            for (SeasonOperation op : operations) {
                MockHttpServletResponse r = send(HttpMethod.valueOf(op.method()), op.path(seasonA), stranger, op.body(orderBody()));
                assertThat(r.getStatus()).as("stranger on " + op.method() + " " + op.template()).isEqualTo(404);
                assertThat(r.getContentType()).startsWith("application/problem+json");
                assertThat(JsonMapper.shared().readTree(r.getContentAsString()).get("code").asString()).isEqualTo("NOT_FOUND");
            }
        }

        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void 주인은_모든_시즌_연산에서_404가_아니다() {
        for (SeasonOperation op : seasonOperations()) {
            MockHttpServletResponse r = send(HttpMethod.valueOf(op.method()), op.path(seasonA), tokenA, op.body(orderBody()));
            assertThat(r.getStatus()).as("A on " + op.method() + " " + op.template()).isNotEqualTo(404).isLessThan(500);
        }
    }

    @Test
    void 알_수_없는_회원의_토큰은_목록_조회_시작이_모두_401이다() throws Exception {
        String ghost = tokens.user(999_999_999L);
        for (var call : List.of(
                Map.entry(HttpMethod.GET, "/api/v1/seasons"),
                Map.entry(HttpMethod.GET, "/api/v1/seasons/" + seasonA),
                Map.entry(HttpMethod.POST, "/api/v1/seasons"))) {
            String body = call.getKey() == HttpMethod.POST ? START_BODY : null;
            MockHttpServletResponse r = send(call.getKey(), call.getValue(), ghost, body);
            MockHttpServletResponse anonymous = send(call.getKey(), call.getValue(), null, body);
            assertThat(r.getStatus()).as(call.toString()).isEqualTo(401);
            assertThat(r.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(r.getContentAsString()).as("same body as the security 401").isEqualTo(anonymous.getContentAsString());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id NOT IN (?, ?)", Long.class, memberA, memberB))
                .as("no season row for the unknown member").isZero();
    }

    @Test
    void 알_수_없는_회원의_시작은_회원도_시즌도_만들지_않는다() {
        long members = jdbc.queryForObject("SELECT count(*) FROM member", Long.class);
        long seasons = jdbc.queryForObject("SELECT count(*) FROM season", Long.class);
        assertThat(send(HttpMethod.POST, "/api/v1/seasons", tokens.user(999_999_999L), START_BODY).getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Long.class)).isEqualTo(members);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season", Long.class)).isEqualTo(seasons);
    }
}
