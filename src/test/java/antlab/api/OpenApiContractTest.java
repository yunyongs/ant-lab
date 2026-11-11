package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.OpenApiDiff;
import antlab.support.OpenApiNormalizer;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** 저장소 계약(docs/api/openapi.yaml)과 코드가 만든 명세(/v3/api-docs)가 같은지 대조한다. 계약이 정답이다. */
@IntegrationTest
class OpenApiContractTest {

    static final Path CONTRACT = Path.of("docs/api/openapi.yaml");

    @Autowired
    MockMvcTester mvc;

    private JsonNode codeSpec() throws Exception {
        String json = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        return OpenApiNormalizer.fromJson(json);
    }

    @Test
    void 코드_명세와_저장소_계약의_차이가_없다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(CONTRACT));
        assertThat(OpenApiDiff.diff(yaml, codeSpec())).as("계약(yaml)과 코드 명세의 차이").isEmpty();
    }

    @Test
    void 모든_운영이_같은_공통_500_응답을_참조한다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(CONTRACT));
        for (var spec : java.util.Map.of("계약", yaml, "코드", codeSpec()).entrySet()) {
            assertThat(spec.getValue().path("components").path("responses").has("InternalError"))
                    .as(spec.getKey() + " components.responses.InternalError").isTrue();
            int operations = 0;
            for (var path : spec.getValue().path("paths").properties()) {
                for (var method : path.getValue().properties()) {
                    operations++;
                    assertThat(method.getValue().path("responses").path("500").path("$ref").asString())
                            .as(spec.getKey() + " " + method.getKey() + " " + path.getKey())
                            .isEqualTo("#/components/responses/InternalError");
                }
            }
            assertThat(operations).isPositive();
        }
    }

    @Test
    void 계약_yaml이_OpenAPI_3_1_모델로_읽힌다() throws Exception {
        OpenAPI model = Yaml31.mapper().readValue(Files.readString(CONTRACT), OpenAPI.class);
        assertThat(model.getOpenapi()).startsWith("3.1");
        assertThat(model.getPaths()).containsKey("/api/v1/pool");
        assertThat(model.getComponents().getSchemas()).containsKeys("PoolDto", "PoolAssetDto", "Problem");
    }
}
