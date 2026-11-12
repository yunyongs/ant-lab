package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.DescriptionParity;
import antlab.support.IntegrationTest;
import antlab.support.OpenApiNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
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

    @Test
    void 계약의_모든_설명은_영어가_먼저이고_한국어가_뒤따른다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        Map<String, String> texts = new TreeMap<>(DescriptionParity.texts(yaml, false, yaml));
        texts.put("/info/description", yaml.path("info").path("description").asString());
        assertThat(DescriptionParity.languageIssues(texts)).as("texts that are not English first, then Korean").isEmpty();
    }

    @Test
    void 영어_없는_문장과_한국어_없는_문장은_보고된다() {
        assertThat(DescriptionParity.languageIssues(Map.of(
                "/a", "기준일 종목 풀",
                "/b", "turnNo 가 틀렸다 — NOT_FOUND",
                "/c", "Asset pool / 종목 풀",
                "/d", "Asset pool")))
                .containsExactly("/a — no English before the Korean", "/b — no English before the Korean",
                        "/d — no Korean");
    }

    @Test
    void 문단_경계가_다르면_보고되고_줄바꿈만_다르면_통과한다() throws Exception {
        JsonNode yaml = OpenApiNormalizer.fromYaml(Files.readString(OpenApiContractTest.CONTRACT));
        JsonNode code = codeSpec();
        String pointer = "/paths/~1api~1v1~1pool/get";
        String original = code.at(pointer).path("description").asString();
        // same words, other line breaks: still equal
        ((ObjectNode) code.at(pointer)).put("description", original.replace(" ", "\n"));
        assertThat(DescriptionParity.diff(yaml, code)).as("line folding is ignored").isEmpty();
        // the blank line between the English and the Korean paragraph turned into a plain space: reported
        assertThat(original).contains("\n\n");
        ((ObjectNode) code.at(pointer)).put("description", original.replace("\n\n", " "));
        assertThat(DescriptionParity.diff(yaml, code)).singleElement().asString()
                .startsWith("/paths//api/v1/pool/get/description — yaml: ");
    }
}
