package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/** Static checks that the management port cannot end up on a LAN address or in a published compose port. No Spring context. */
class ManagementExposureSettingsTest {

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    }

    private static PropertySource<?> byProfile(String profile) throws IOException {
        return documents().stream()
                .filter(d -> profile.equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiService() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yaml")));
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get("api");
    }

    @Test
    void local은_관리_포트를_루프백에_묶는다() throws IOException {
        assertThat(byProfile("local").getProperty("management.server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void 공통과_prod는_관리_주소를_정하지_않고_기본_포트는_8081이다() throws IOException {
        PropertySource<?> common = documents().get(0);
        assertThat(common.getProperty("management.server.address")).isNull();
        assertThat(byProfile("prod").getProperty("management.server.address")).isNull();
        assertThat(common.getProperty("management.server.port")).isEqualTo("${ANTLAB_MANAGEMENT_PORT:8081}");
    }

    @Test
    void compose_api는_8081을_게시하지_않는다() throws IOException {
        assertThat(apiService().get("ports")).isEqualTo(List.of("127.0.0.1:${ANTLAB_API_PORT:-8080}:8080"));
        assertThat(apiService().toString()).doesNotContain("ANTLAB_MANAGEMENT_PORT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compose_api_헬스체크는_API_포트의_readyz를_부른다() throws IOException {
        Map<String, Object> healthcheck = (Map<String, Object>) apiService().get("healthcheck");
        String test = healthcheck.get("test").toString();
        assertThat(test).contains("http://127.0.0.1:8080/readyz").doesNotContain("/actuator/");
    }
}
