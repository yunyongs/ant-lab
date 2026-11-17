package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.CorsFilter;
import jakarta.servlet.Filter;

/**
 * The runner chain must come first and catch exactly the runner prefix, and the order must be written down, not left to
 * the order beans happen to be registered in. Checked three ways: the real chain list of the filter proxy, the declared
 * order of the two chain methods, and the answers of real requests.
 */
@IntegrationTest
@Transactional
class AgentRunnerChainOrderTest {

    private static final String RUNNER_CONFIG = "antlab.api.security.AgentRunnerSecurityConfig";
    private static final String API_CONFIG = "antlab.api.security.SecurityConfig";

    /** Paths that belong to the runner chain, with the edges of the prefix. */
    static final String[] RUNNER_PATHS = {
            "/internal/agent-runner", "/internal/agent-runner/", "/internal/agent-runner/other",
            "/internal/agent-runner/v1", "/internal/agent-runner/v2/agents", BASE + "/nothing", AGENTS,
            AGENTS + "/1/seasons", AGENTS + "/1/turns/2/decision"};

    /** Paths that must stay with the general chain, including look-alikes of the prefix. */
    static final String[] OTHER_PATHS = {
            "/", "/api/v1/seasons", "/api/v1/seasons/1", "/api/v1/auth/login", "/actuator/health", "/v3/api-docs",
            "/internal", "/internal/", "/internal/other", "/internal/agent-runner-x", "/internal/agent-runnerx/v1",
            "/internal/agent", "/internal/agents/v1/agents", "/api/v1/internal/agent-runner/v1/agents",
            "/x/internal/agent-runner/v1/agents"};

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers members;
    @Autowired
    FilterChainProxy proxy;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    // ---- the chain list of the filter proxy ----

    private static boolean has(SecurityFilterChain chain, Class<? extends Filter> type) {
        return chain.getFilters().stream().anyMatch(type::isInstance);
    }

    private static boolean hasRunnerFilter(SecurityFilterChain chain) {
        return chain.getFilters().stream().anyMatch(f -> f.getClass().getSimpleName().equals("RunnerTokenFilter"));
    }

    private SecurityFilterChain runnerChain() {
        List<SecurityFilterChain> found = proxy.getFilterChains().stream()
                .filter(AgentRunnerChainOrderTest::hasRunnerFilter).toList();
        assertThat(found).as("chains holding the runner token filter").hasSize(1);
        return found.get(0);
    }

    private SecurityFilterChain apiChain() {
        List<SecurityFilterChain> found = proxy.getFilterChains().stream()
                .filter(c -> has(c, BearerTokenAuthenticationFilter.class)).toList();
        assertThat(found).as("chains holding the bearer token filter").hasSize(1);
        return found.get(0);
    }

    private SecurityFilterChain firstMatch(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return proxy.getFilterChains().stream().filter(c -> c.matches(request)).findFirst().orElse(null);
    }

    @Test
    void the_runner_chain_stands_before_the_general_chain() {
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        assertThat(chains.indexOf(runnerChain())).isLessThan(chains.indexOf(apiChain()));
        assertThat(chains.indexOf(runnerChain())).isZero();
    }

    @Test
    void the_runner_chain_catches_exactly_the_runner_prefix() {
        SecurityFilterChain runner = runnerChain();
        for (String uri : RUNNER_PATHS) {
            assertThat(runner.matches(new MockHttpServletRequest("GET", uri))).as(uri).isTrue();
            assertThat(firstMatch(uri)).as("first chain for " + uri).isSameAs(runner);
        }
        for (String uri : OTHER_PATHS) {
            assertThat(runner.matches(new MockHttpServletRequest("GET", uri))).as(uri).isFalse();
            assertThat(firstMatch(uri)).as("first chain for " + uri).isSameAs(apiChain());
        }
    }

    @Test
    void the_matcher_does_not_depend_on_the_http_method() {
        SecurityFilterChain runner = runnerChain();
        for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"}) {
            assertThat(runner.matches(new MockHttpServletRequest(method, AGENTS))).as(method).isTrue();
            assertThat(runner.matches(new MockHttpServletRequest(method, "/api/v1/seasons"))).as(method).isFalse();
        }
    }

    @Test
    void the_runner_chain_does_not_share_filters_or_settings_with_the_general_chain() {
        SecurityFilterChain runner = runnerChain();
        SecurityFilterChain api = apiChain();
        Set<Filter> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        mine.addAll(runner.getFilters());
        assertThat(api.getFilters()).noneMatch(mine::contains);
        // only the runner token reaches the runner paths
        assertThat(has(runner, BearerTokenAuthenticationFilter.class)).isFalse();
        assertThat(hasRunnerFilter(api)).isFalse();
        // no CORS, CSRF or session machinery on either chain: the runner is a server, not a browser
        for (Class<? extends Filter> type : List.of(CorsFilter.class, CsrfFilter.class)) {
            assertThat(has(runner, type)).as(type.getSimpleName() + " on runner chain").isFalse();
            assertThat(has(api, type)).as(type.getSimpleName() + " on general chain").isFalse();
        }
        List<Class<?>> order = new ArrayList<>();
        runner.getFilters().forEach(f -> order.add(f.getClass()));
        int token = indexOfSimpleName(order, "RunnerTokenFilter");
        int gate = order.indexOf(AuthorizationFilter.class);
        assertThat(token).isGreaterThanOrEqualTo(0);
        assertThat(gate).isGreaterThan(token);
    }

    private static int indexOfSimpleName(List<Class<?>> types, String name) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).getSimpleName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void the_runner_chain_is_a_default_security_chain_with_a_path_matcher() {
        assertThat(runnerChain()).isInstanceOf(DefaultSecurityFilterChain.class);
    }

    // ---- the declared order, independent of bean registration order ----

    private static int declaredOrder(String config) throws Exception {
        Class<?> type = Class.forName(config);
        List<Method> chainMethods = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> SecurityFilterChain.class.isAssignableFrom(m.getReturnType())).toList();
        assertThat(chainMethods).as("chain methods of " + config).hasSize(1);
        Order order = chainMethods.get(0).getAnnotation(Order.class);
        return order == null ? Ordered.LOWEST_PRECEDENCE : order.value();
    }

    @Test
    void the_runner_chain_declares_an_explicit_order() throws Exception {
        Method method = Arrays.stream(Class.forName(RUNNER_CONFIG).getDeclaredMethods())
                .filter(m -> SecurityFilterChain.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        assertThat(method.getAnnotation(Order.class)).as("@Order on the runner chain").isNotNull();
    }

    @Test
    void the_runner_chain_is_declared_before_the_general_chain() throws Exception {
        int runner = declaredOrder(RUNNER_CONFIG);
        int api = declaredOrder(API_CONFIG);
        assertThat(runner).isLessThan(api);
        // not at the very end, so a chain added later without an order cannot move ahead of the runner chain by accident
        assertThat(runner).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    // ---- real requests ----

    private MockHttpServletResponse send(HttpMethod method, String uri, String authorization, String runnerToken,
            boolean anonymous) {
        var request = mvc.method(method).uri(uri).contentType("application/json").content(REGISTER_BODY);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        if (runnerToken != null) {
            request = request.header(HEADER, runnerToken);
        }
        if (anonymous) {
            request = request.requestAttr(ANONYMOUS, true);
        }
        return request.exchange().getResponse();
    }

    private void assertUnauthenticated(MockHttpServletResponse r, String label) throws Exception {
        assertThat(r.getStatus()).as(label).isEqualTo(401);
        assertThat(json(r).get("code").asString()).as(label).isEqualTo("UNAUTHENTICATED");
        assertThat(r.getContentAsString()).as(label).doesNotContain(TOKEN);
    }

    @Test
    void the_service_token_reaches_the_runner_path_and_a_human_token_reaches_the_public_path() throws Exception {
        assertThat(send(HttpMethod.POST, AGENTS, null, TOKEN, true).getStatus()).isEqualTo(201);
        String user = "Bearer " + tokens.user(members.defaultId());
        assertThat(send(HttpMethod.GET, "/api/v1/seasons", user, null, false).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void a_human_token_opens_no_runner_path(String method) throws Exception {
        for (String bearer : new String[] {"Bearer " + tokens.user(members.defaultId()),
                "Bearer " + tokens.admin(members.defaultId())}) {
            for (String uri : new String[] {AGENTS, AGENTS + "/1/seasons", BASE + "/nothing", "/internal/agent-runner"}) {
                assertUnauthenticated(send(HttpMethod.valueOf(method), uri, bearer, null, true), method + " " + uri);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void the_service_token_opens_no_general_path(String method) throws Exception {
        for (String uri : new String[] {"/api/v1/seasons", "/api/v1/seasons/1", "/api/v1/seasons/1/holdings",
                "/api/v1/dev/anything", "/internal/other", "/anything"}) {
            assertUnauthenticated(send(HttpMethod.valueOf(method), uri, null, TOKEN, true), method + " " + uri);
        }
        // a broken human token next to the service token does not change the answer either
        assertUnauthenticated(send(HttpMethod.GET, "/api/v1/seasons", "Bearer broken", TOKEN, true), "broken bearer");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", " ", "wrong", "SHORT", "LONGER"})
    void a_missing_wrong_or_differently_long_token_is_401_on_every_runner_path(String kind) throws Exception {
        String token = switch (kind) {
            case "" -> null;
            case "SHORT" -> TOKEN.substring(0, TOKEN.length() - 1);
            case "LONGER" -> TOKEN + "0";
            default -> kind;
        };
        for (String uri : RUNNER_PATHS) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST}) {
                assertUnauthenticated(send(method, uri, null, token, true), method + " " + uri);
            }
        }
    }

    @Test
    void a_runner_path_sets_no_cookie_and_no_cors_headers_and_opens_no_session() throws Exception {
        for (String token : new String[] {null, "wrong", TOKEN}) {
            var request = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                    .header("Origin", "https://evil.example").requestAttr(ANONYMOUS, true);
            if (token != null) {
                request = request.header(HEADER, token);
            }
            var result = request.exchange();
            MockHttpServletResponse r = result.getResponse();
            assertThat(r.getHeader("Set-Cookie")).as("cookie " + token).isNull();
            assertThat(r.getHeader("Access-Control-Allow-Origin")).as("cors " + token).isNull();
            assertThat(r.getHeader("Access-Control-Allow-Credentials")).as("cors credentials " + token).isNull();
            assertThat(result.getRequest().getSession(false)).as("session " + token).isNull();
        }
        // a preflight is not answered with permission, with or without the token
        for (String token : new String[] {null, TOKEN}) {
            var request = mvc.options().uri(AGENTS).header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "POST").requestAttr(ANONYMOUS, true);
            if (token != null) {
                request = request.header(HEADER, token);
            }
            MockHttpServletResponse r = request.exchange().getResponse();
            assertThat(r.getHeader("Access-Control-Allow-Origin")).as("preflight " + token).isNull();
            assertThat(r.getHeader("Set-Cookie")).isNull();
        }
    }

    @Test
    void a_post_without_a_csrf_token_is_judged_by_the_service_token_alone() throws Exception {
        // the runner chain has no CSRF check: the token decides, in both directions
        assertThat(send(HttpMethod.POST, AGENTS, null, TOKEN, true).getStatus()).isEqualTo(201);
        assertUnauthenticated(send(HttpMethod.POST, AGENTS, null, "wrong", true), "wrong token");
    }
}
