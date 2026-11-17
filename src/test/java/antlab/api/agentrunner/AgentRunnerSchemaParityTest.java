package antlab.api.agentrunner;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.AgentDto;
import antlab.agent.AgentPartDto;
import antlab.agent.AgentSeasonConfigRequest;
import antlab.agent.RecentTradeDto;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.RejectDto;
import antlab.agent.StartAgentSeasonRequest;
import antlab.agent.AgentTurnViewDto;
import antlab.api.config.OpenApiConfig;
import antlab.common.error.ErrorCode;
import antlab.support.OpenApiNormalizer;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** docs/api/agent-runner.yaml against the DTO records, the public contract and the error codes (no Spring context). */
class AgentRunnerSchemaParityTest {

    private static final Path RUNNER = Path.of("docs/api/agent-runner.yaml");
    private static final Path PUBLIC = Path.of("docs/api/openapi.yaml");

    private static JsonNode schemas(Path file) throws Exception {
        return OpenApiNormalizer.fromYaml(Files.readString(file)).path("components").path("schemas");
    }

    /** The runner file writes an enum inline where the public file refers to a named one; both mean the same values. */
    private static JsonNode inlineEnums(JsonNode properties, JsonNode schemas) {
        ObjectNode copy = ((ObjectNode) properties).deepCopy();
        for (String name : keys(properties)) {
            String ref = properties.path(name).path("$ref").asString("");
            JsonNode target = schemas.path(ref.substring(ref.lastIndexOf('/') + 1));
            if (target.has("enum")) {
                copy.set(name, target);
            }
        }
        return copy;
    }

    /** The shapes must be equal; the prose may differ. */
    private static JsonNode withoutDescriptions(JsonNode node) {
        JsonNode copy = node.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("description");
            object.forEach(AgentRunnerSchemaParityTest::strip);
        } else if (node.isArray()) {
            node.forEach(AgentRunnerSchemaParityTest::strip);
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.propertyNames().forEach(out::add);
        return out;
    }

    private static Set<String> components(Class<? extends Record> type) {
        Set<String> out = new TreeSet<>();
        Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).forEach(out::add);
        return out;
    }

    private static void assertSchemaMatches(String schema, Class<? extends Record> type) throws Exception {
        JsonNode node = schemas(RUNNER).path(schema);
        assertThat(node.isMissingNode()).as(schema).isFalse();
        assertThat(keys(node.path("properties"))).as(schema + " properties").isEqualTo(components(type));
        assertThat(keys(node.path("properties"))).as(schema + " required is within properties")
                .containsAll(names(node.path("required")));
    }

    @Test
    void the_request_and_response_schemas_have_the_properties_of_their_records() throws Exception {
        assertSchemaMatches("RegisterAgentRequest", RegisterAgentRequest.class);
        assertSchemaMatches("AgentDto", AgentDto.class);
        assertSchemaMatches("StartAgentSeasonRequest", StartAgentSeasonRequest.class);
        assertSchemaMatches("AgentSeasonConfig", AgentSeasonConfigRequest.class);
        assertSchemaMatches("AgentTurnViewDto", AgentTurnViewDto.class);
        assertSchemaMatches("AgentPartDto", AgentPartDto.class);
        assertSchemaMatches("RecentTradeDto", RecentTradeDto.class);
        assertSchemaMatches("RejectDto", RejectDto.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SeasonDto", "FormationDto", "TurnResultDto", "HoldingsDto", "HoldingDto", "TurnPricesDto",
            "TurnPriceDto", "PoolAssetDto"})
    void the_shared_schemas_equal_the_public_ones(String schema) throws Exception {
        JsonNode runnerSchemas = schemas(RUNNER);
        JsonNode openSchemas = schemas(PUBLIC);
        JsonNode runner = runnerSchemas.path(schema);
        JsonNode open = openSchemas.path(schema);
        assertThat(runner.isMissingNode()).isFalse();
        assertThat(withoutDescriptions(inlineEnums(runner.path("properties"), runnerSchemas)))
                .isEqualTo(withoutDescriptions(inlineEnums(open.path("properties"), openSchemas)));
        assertThat(names(runner.path("required"))).isEqualTo(names(open.path("required")));
    }

    @Test
    void the_runner_contract_uses_the_3_1_null_syntax() throws Exception {
        assertThat(Files.readString(RUNNER)).doesNotContain("nullable");
        assertThat(schemas(RUNNER).path("AgentSeasonConfig").path("properties").path("modelSeed").path("type"))
                .isEqualTo(OpenApiNormalizer.fromYaml("t: [integer, 'null']").path("t"));
    }

    @Test
    void the_error_code_enums_follow_the_java_enum_and_the_runner_only_set() throws Exception {
        Set<String> all = new TreeSet<>();
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(all::add);
        Set<String> runner = names(schemas(RUNNER).path("ErrorCode").path("enum"));
        Set<String> open = names(schemas(PUBLIC).path("ErrorCode").path("enum"));

        assertThat(runner).isEqualTo(all);
        Set<String> runnerOnly = new TreeSet<>(runner);
        runnerOnly.removeAll(open);
        assertThat(OpenApiConfig.RUNNER_ONLY_CODES).isEqualTo(runnerOnly);
        assertThat(open).doesNotContainAnyElementsOf(OpenApiConfig.RUNNER_ONLY_CODES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AgentTurnViewDto", "AgentPartDto", "RecentTradeDto", "RejectDto"})
    void every_component_of_an_agent_response_schema_is_required(String schema) throws Exception {
        Class<? extends Record> type = switch (schema) {
            case "AgentTurnViewDto" -> AgentTurnViewDto.class;
            case "AgentPartDto" -> AgentPartDto.class;
            case "RecentTradeDto" -> RecentTradeDto.class;
            default -> RejectDto.class;
        };
        assertThat(names(schemas(RUNNER).path(schema).path("required"))).isEqualTo(components(type));
    }

    @Test
    void the_nullable_parts_and_the_pool_errors_of_the_turn_view_are_written_down() throws Exception {
        JsonNode s = schemas(RUNNER);
        assertThat(s.path("AgentTurnViewDto").path("properties").path("lastTurn").path("oneOf"))
                .isEqualTo(OpenApiNormalizer.fromYaml("t: [{$ref: '#/components/schemas/TurnResultDto'}, {type: 'null'}]").path("t"));
        assertThat(s.path("AgentPartDto").path("properties").path("memory").path("type").asString()).isEqualTo("null");
        assertThat(s.path("RejectDto").path("properties").path("index").path("type"))
                .isEqualTo(OpenApiNormalizer.fromYaml("t: [integer, 'null']").path("t"));
        JsonNode doc = OpenApiNormalizer.fromYaml(Files.readString(RUNNER));
        JsonNode codes = doc.path("paths").path("/internal/agent-runner/v1/agents/{agentId}/seasons/{seasonId}/turn-view")
                .path("get").path("responses").path("409").path("x-error-codes");
        assertThat(names(codes)).containsExactlyInAnyOrder("POOL_NOT_LOADED", "POOL_NOT_JUDGED");
    }
}
