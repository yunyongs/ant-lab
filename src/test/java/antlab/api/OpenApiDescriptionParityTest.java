package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.DescriptionParity;
import antlab.support.IntegrationTest;
import antlab.support.OpenApiNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operation summary/description and schema/property descriptions in the contract equal the text the annotations
 * produce. The structural diff ignores prose; this test keeps it from drifting.
 */
@IntegrationTest
class OpenApiDescriptionParityTest {

    @Autowired
    MockMvcTester mvc;

    private JsonNode codeSpec() throws Exception {
        return OpenApiNormalizer.fromJson(mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString());
    }

    @Test
    void 계약과_코드_명세의_설명_문장이_같다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        assertThat(DescriptionParity.diff(yaml, codeSpec())).as("description differences (yaml vs code)").isEmpty();
    }

    @Test
    void 비교하는_문장이_충분히_많다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        // vacuity guard: 12 operations with summary and description, plus schema and property descriptions
        assertThat(DescriptionParity.texts(yaml, false, yaml).size()).isGreaterThan(230);
    }

    @Test
    void 코드_쪽_문장_한_글자가_다르면_JSON_포인터와_함께_보고된다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        JsonNode code = codeSpec();
        String pointer = "/paths/~1api~1v1~1pool/get/summary";
        String original = code.at(pointer).asString();
        assertThat(original).isNotBlank();
        ((ObjectNode) code.at("/paths/~1api~1v1~1pool/get")).put("summary", original + "!");
        assertThat(DescriptionParity.diff(yaml, code)).singleElement().asString()
                .startsWith("/paths//api/v1/pool/get/summary — yaml: " + original.strip() + " / code: " + original.strip() + "!");
    }

    @Test
    void 응답_설명_한_글자가_다르면_보고된다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        JsonNode code = codeSpec();
        String pointer = "/paths/~1api~1v1~1seasons~1{seasonId}~1orders/post/responses/409";
        String original = code.at(pointer).path("description").asString();
        assertThat(original).isNotBlank();
        ((ObjectNode) code.at(pointer)).put("description", original + "!");
        assertThat(DescriptionParity.diff(yaml, code)).singleElement().asString()
                .startsWith("/paths//api/v1/seasons/{seasonId}/orders/post/responses/409/description — yaml: ");
    }

    @Test
    void InternalError_설명도_비교한다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        JsonNode code = codeSpec();
        ObjectNode internal = (ObjectNode) code.at("/components/responses/InternalError");
        internal.put("description", internal.path("description").asString() + "!");
        assertThat(DescriptionParity.diff(yaml, code)).singleElement().asString()
                .startsWith("/components/responses/InternalError/description — yaml: ");
    }

    @Test
    void 코드_쪽에_문장이_없으면_absent로_보고된다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        JsonNode code = codeSpec();
        ((ObjectNode) code.at("/paths/~1api~1v1~1pool/get")).remove("description");
        assertThat(DescriptionParity.diff(yaml, code)).singleElement().asString()
                .startsWith("/paths//api/v1/pool/get/description — yaml: ").endsWith("/ code: (absent)");
    }
}
