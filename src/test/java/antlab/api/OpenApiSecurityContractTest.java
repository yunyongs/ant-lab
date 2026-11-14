package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.security.PublicPaths;
import antlab.support.IntegrationTest;
import antlab.support.OpenApiNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The security layer of the contract: {@code bearerAuth} and, on exactly the operations that are not public, the
 * {@code security} requirement and the shared 401. The normalizer ignores {@code security}, so it is pinned here, in the yaml and in
 * the springdoc output alike.
 */
@IntegrationTest
class OpenApiSecurityContractTest {

    @Autowired
    MockMvcTester mvc;

    private Map<String, JsonNode> specs() throws Exception {
        String json = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        return Map.of("계약", OpenApiNormalizer.fromYaml(Files.readString(Path.of("docs/api/openapi.yaml"))),
                "코드", OpenApiNormalizer.fromJson(json));
    }

    @Test
    void bearerAuth_스킴이_있다() throws Exception {
        for (var spec : specs().entrySet()) {
            JsonNode scheme = spec.getValue().path("components").path("securitySchemes").path("bearerAuth");
            assertThat(scheme.path("type").asString()).as(spec.getKey()).isEqualTo("http");
            assertThat(scheme.path("scheme").asString()).as(spec.getKey()).isEqualTo("bearer");
            assertThat(scheme.path("bearerFormat").asString()).as(spec.getKey()).isEqualTo("JWT");
            assertThat(spec.getValue().has("security")).as(spec.getKey() + " has no top-level security").isFalse();
        }
    }

    @Test
    void 공개_연산_다섯을_뺀_모든_연산이_security와_401을_가진다() throws Exception {
        for (var spec : specs().entrySet()) {
            Set<String> secured = new TreeSet<>();
            int operations = 0;
            for (var path : spec.getValue().path("paths").properties()) {
                boolean isPublic = PublicPaths.CONTRACT_PUBLIC.contains(path.getKey());
                for (var method : path.getValue().properties()) {
                    operations++;
                    String where = spec.getKey() + " " + method.getKey().toUpperCase() + " " + path.getKey();
                    JsonNode security = method.getValue().path("security");
                    JsonNode unauthenticated = method.getValue().path("responses").path("401");
                    if (isPublic) {
                        assertThat(security.isMissingNode()).as(where + " has no security").isTrue();
                        assertThat(unauthenticated.path("$ref").asString()).as(where + " has no shared 401")
                                .isNotEqualTo("#/components/responses/Unauthenticated");
                    } else {
                        secured.add(method.getKey().toUpperCase() + " " + path.getKey());
                        assertThat(security.size()).as(where).isEqualTo(1);
                        assertThat(security.get(0).has("bearerAuth")).as(where).isTrue();
                        assertThat(unauthenticated.path("$ref").asString()).as(where)
                                .isEqualTo("#/components/responses/Unauthenticated");
                    }
                }
            }
            assertThat(operations).as(spec.getKey()).isEqualTo(16);
            assertThat(secured).as(spec.getKey() + " secured operations").hasSize(11);
            JsonNode response = spec.getValue().path("components").path("responses").path("Unauthenticated");
            assertThat(response.path("content").has("application/problem+json")).as(spec.getKey()).isTrue();
            if (spec.getKey().equals("계약")) {
                assertThat(response.path("x-error-codes").get(0).asString()).isEqualTo("UNAUTHENTICATED");
            }
        }
    }
}
