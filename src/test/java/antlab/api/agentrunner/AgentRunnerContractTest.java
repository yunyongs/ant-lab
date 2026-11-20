package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.AgentDto;
import antlab.agent.AgentTurnViewDto;
import antlab.agent.decision.DecisionResultDto;
import antlab.season.AbandonResultDto;
import antlab.season.TurnResultDto;
import antlab.api.config.OpenApiConfig;
import antlab.season.SeasonDto;
import antlab.support.ContractErrorCodes;
import antlab.support.ExposureCatalog;
import antlab.support.ExposureRules;
import antlab.support.IntegrationTest;
import antlab.support.OpenApiNormalizer;
import jakarta.persistence.EntityManager;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * docs/api/agent-runner.yaml against the code: the operations are the same, the answers are the listed codes, the public
 * description knows nothing of the runner, and the response records stay inside the exposure rules.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentRunnerContractTest {

    private static final String PREFIX = "/internal/agent-runner/";
    private static final Path CONTRACT = Path.of("docs/api/agent-runner.yaml");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private static JsonNode contract() throws Exception {
        return OpenApiNormalizer.fromYaml(Files.readString(CONTRACT));
    }

    private Set<String> mappedOperations() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                if (pattern.startsWith(PREFIX)) {
                    e.getKey().getMethodsCondition().getMethods().forEach(m -> out.add(m.name() + " " + pattern));
                }
            }
        }
        return out;
    }

    @Test
    void the_contract_and_the_controllers_have_the_same_operations() throws Exception {
        Set<String> documented = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : contract().path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                documented.add(op.getKey().toUpperCase() + " " + path.getKey());
            }
        }
        assertThat(documented).isNotEmpty();
        assertThat(mappedOperations()).isEqualTo(documented);
    }

    @Test
    void the_error_layer_is_consistent_with_the_status_mapping() throws Exception {
        assertThat(ContractErrorCodes.violations(contract(), OpenApiNormalizer.fromYaml(
                Files.readString(Path.of("docs/api/openapi.yaml"))))).isEmpty();
    }

    @Test
    void every_answer_the_runner_gets_is_listed_by_the_contract() throws Exception {
        JsonNode doc = contract();
        String register = "POST " + AGENTS;
        String start = "POST " + AGENTS + "/{agentId}/seasons";
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        String startUri = AGENTS + "/" + agent + "/seasons";

        assertListed(doc, register, post(mvc, AGENTS, "wrong", REGISTER_BODY, true));
        assertListed(doc, start, post(mvc, startUri, "wrong", START_BODY, true));
        assertListed(doc, register, post(mvc, AGENTS, "{}"));
        assertListed(doc, start, post(mvc, startUri, "{}"));
        assertListed(doc, start, post(mvc, AGENTS + "/987654321/seasons", START_BODY));

        String turnView = "GET " + AGENTS + "/{agentId}/seasons/{seasonId}/turn-view";
        long season = json(post(mvc, startUri, START_BODY)).get("id").asLong();
        String viewUri = startUri + "/" + season + "/turn-view";
        assertListed(doc, turnView, get(viewUri, "wrong"));
        assertListed(doc, turnView, get(AGENTS + "/" + agent + "/seasons/987654321/turn-view", TOKEN));
        assertListed(doc, turnView, get(AGENTS + "/987654321/seasons/" + season + "/turn-view", TOKEN));
        assertListed(doc, turnView, get(AGENTS + "/" + agent + "/seasons/abc/turn-view", TOKEN));
        em.flush();
        jdbc.update("UPDATE agent_profile SET status = 'RETIRED' WHERE member_id = ?", agent);
        em.clear();
        MockHttpServletResponse retired = post(mvc, startUri, START_BODY);
        assertListed(doc, start, retired);
        assertThat(json(retired).get("code").asString()).isEqualTo("AGENT_RETIRED");
    }

    @Test
    void framework_errors_are_listed_too() throws Exception {
        JsonNode doc = contract();
        // a request the security firewall rejects (answered by ProblemRequestRejectedHandler, see FirewallRejectionTest)
        assertThat(ContractErrorCodes.isListed(doc, "POST " + AGENTS, 400, "MALFORMED_REQUEST")).isTrue();
        var wrongMethod = mvc.get().uri(AGENTS).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange()
                .getResponse();
        assertThat(wrongMethod.getStatus()).isEqualTo(405);
        assertThat(ContractErrorCodes.isListed(doc, "POST " + AGENTS, 405, json(wrongMethod).get("code").asString()))
                .isTrue();
        var wrongType = mvc.post().uri(AGENTS).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true)
                .contentType(MediaType.TEXT_PLAIN).content("x").exchange().getResponse();
        assertThat(wrongType.getStatus()).isEqualTo(415);
        assertThat(ContractErrorCodes.isListed(doc, "POST " + AGENTS, 415, json(wrongType).get("code").asString()))
                .isTrue();
        var notAcceptable = mvc.post().uri(AGENTS).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML).content(REGISTER_BODY)
                .exchange().getResponse();
        assertThat(notAcceptable.getStatus()).isEqualTo(406);
    }

    private MockHttpServletResponse get(String uri, String token) {
        return mvc.get().uri(uri).header(HEADER, token).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private static void assertListed(JsonNode doc, String operation, MockHttpServletResponse response) throws Exception {
        String code = json(response).get("code").asString();
        assertThat(ContractErrorCodes.isListed(doc, operation, response.getStatus(), code))
                .as(operation + " " + response.getStatus() + " " + code).isTrue();
    }

    @Test
    void the_public_description_knows_nothing_of_the_runner() throws Exception {
        String publicSpec = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        assertThat(publicSpec).doesNotContain("/internal").doesNotContain("agent-runner")
                .doesNotContain("registerAgent").doesNotContain("X-Antlab-Runner-Token");
        for (String code : OpenApiConfig.RUNNER_ONLY_CODES) {
            assertThat(publicSpec).as(code).doesNotContain(code);
        }
    }

    @Test
    void the_runner_responses_stay_inside_the_exposure_rules() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        Set<Class<?>> types = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            boolean runner = e.getKey().getPathPatternsCondition().getPatternValues().stream()
                    .anyMatch(p -> p.startsWith(PREFIX));
            if (!runner) {
                continue;
            }
            ResolvableType type = ResolvableType.forMethodReturnType(e.getValue().getMethod());
            if (ResponseEntity.class.isAssignableFrom(type.toClass())) {
                type = type.getGeneric(0);
            }
            types.add(type.toClass());
        }
        // A new runner response type must be added here on purpose and goes through the catalog.
        assertThat(types).containsExactlyInAnyOrder(AgentDto.class, SeasonDto.class, antlab.agent.AgentSeasonConfigDto.class, AgentTurnViewDto.class,
                DecisionResultDto.class, TurnResultDto.class, AbandonResultDto.class, antlab.league.LeagueDto.class, Void.class);
        assertThat(ExposureRules.check(SeasonDto.class, catalog, false, false)).isEmpty();
        assertThat(ExposureRules.check(AgentTurnViewDto.class, catalog, false, false)).isEmpty();
        assertThat(Arrays.stream(antlab.agent.AgentSeasonConfigDto.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactly("personaKey", "personaVersion", "personaSha256", "modelRoute", "modelRequested",
                        "promptTemplateVersion", "runnerVersion", "memoryVersion", "temperature", "modelSeed", "maxTokens",
                        "turnTimeoutMs", "budgetCalls", "budgetUsd");
        assertThat(ExposureRules.check(DecisionResultDto.class, catalog, false, false)).isEmpty();
        assertThat(ExposureRules.check(TurnResultDto.class, catalog, false, false)).isEmpty();
        assertThat(ExposureRules.check(AbandonResultDto.class, catalog, false, false)).isEmpty();
        // The league answers carry no seed, date, engine or money field: only these components exist.
        assertThat(Arrays.stream(antlab.league.LeagueDto.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactly("id", "name", "status", "pathLength", "maxEntries", "entries");
        assertThat(Arrays.stream(antlab.league.LeagueEntryDto.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactly("memberId", "memberKind", "displayName", "seasonId", "status", "currentTurnNo");
        assertThat(Arrays.stream(AgentDto.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .containsExactly("agentId", "displayName", "personaKey", "modelRoute", "modelId", "status", "ownerMemberId");
    }
}
