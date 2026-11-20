package antlab.api.agentrunner;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers of the runner API tests. The token and owner id are the visibly fake values of application-test.yml. */
public final class AgentRunnerSupport {

    public static final String TOKEN = "fake-runner-token-for-tests-only-0123456789abcdef";
    public static final long OWNER_ID = 910_001L;
    public static final String HEADER = "X-Antlab-Runner-Token";
    public static final String BASE = "/internal/agent-runner/v1";
    public static final String AGENTS = BASE + "/agents";
    public static final String SHA = "a".repeat(64);

    public static final String START_BODY = startBody(SHA);

    private AgentRunnerSupport() {
    }

    /** The owner the test configuration names; inserted with its fixed id, and left alone when it already exists. */
    public static void ensureOwner(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO member (id, email, name, password) OVERRIDING SYSTEM VALUE
                VALUES (?, 'runner-owner@example.invalid', '러너 소유자', '!') ON CONFLICT DO NOTHING""", OWNER_ID);
    }

    public static String registerBody(String persona, String route, String modelId, String name) {
        return "{\"personaKey\": \"" + persona + "\", \"modelRoute\": \"" + route + "\", \"modelId\": \"" + modelId
                + "\", \"displayName\": \"" + name + "\"}";
    }

    public static final String REGISTER_BODY = registerBody("value-investor", "OPENROUTER", "fake/model-1", "가치 투자자");

    public static String startBody(String personaSha256) {
        return """
                {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
                 "config": {"personaVersion": 1, "personaSha256": "%s", "personaDisplayName": "Value",
                   "modelRequested": "fake/model-1",
                   "temperature": 0.7, "maxTokens": 2000, "turnTimeoutMs": 60000, "promptTemplateVersion": "p1",
                   "runnerVersion": "r1", "budgetCalls": 100, "budgetUsd": 1.5}}""".formatted(personaSha256);
    }

    /** A start body that names a league (null = none) and, optionally, a path length and the model the agent is registered with. */
    public static String startBody(String model, Long leagueId, Integer pathLength) {
        return """
                {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}%s%s,
                 "config": {"personaVersion": 1, "personaSha256": "%s", "personaDisplayName": "Value",
                   "modelRequested": "%s",
                   "temperature": 0.7, "maxTokens": 2000, "turnTimeoutMs": 60000, "promptTemplateVersion": "p1",
                   "runnerVersion": "r1", "budgetCalls": 100, "budgetUsd": 1.5}}"""
                .formatted(leagueId == null ? "" : ", \"leagueId\": " + leagueId,
                        pathLength == null ? "" : ", \"pathLength\": " + pathLength, SHA, model);
    }

    /** POST with the given token (null = no header) and no human token unless {@code anonymous} is false. */
    public static MockHttpServletResponse post(MockMvcTester mvc, String uri, String token, String body, boolean anonymous) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(HEADER, token);
        }
        if (anonymous) {
            request = request.requestAttr(ANONYMOUS, true);
        }
        return request.exchange().getResponse();
    }

    public static MockHttpServletResponse post(MockMvcTester mvc, String uri, String body) {
        return post(mvc, uri, TOKEN, body, true);
    }

    public static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonMapper.shared().readTree(response.getContentAsString());
    }

    /** Registers the default agent through the API and returns its id. */
    public static long register(MockMvcTester mvc, String body) throws Exception {
        MockHttpServletResponse r = post(mvc, AGENTS, body);
        return json(r).get("agentId").asLong();
    }
}
