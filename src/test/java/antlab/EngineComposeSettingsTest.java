package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Static checks of how compose wires the api to the engine (M2-06). No Spring context, no Docker. */
class EngineComposeSettingsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yaml")));
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiEnvironment() throws IOException {
        return (Map<String, Object>) services().get("api").get("environment");
    }

    @Test
    void 엔진은_profile_engine과_api_둘_다에_있다() throws IOException {
        assertThat(services().get("engine").get("profiles")).isEqualTo(List.of("engine", "api"));
    }

    @Test
    void api는_엔진_주소와_토큰과_경로_출처를_받고_기본_경로_출처는_java다() throws IOException {
        Map<String, Object> env = apiEnvironment();

        assertThat(env.get("ANTLAB_ENGINE_URL")).isEqualTo("http://engine:8000");
        assertThat(env.get("ANTLAB_ENGINE_TOKEN")).isEqualTo("${ANTLAB_ENGINE_TOKEN:-}");
        assertThat(env.get("ANTLAB_ENGINE_PATH_SOURCE")).isEqualTo("${ANTLAB_ENGINE_PATH_SOURCE:-java}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void api는_엔진_없이_시작한다() throws IOException {
        Map<String, Object> dependsOn = (Map<String, Object>) services().get("api").get("depends_on");

        assertThat(dependsOn).containsOnlyKeys("db");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 엔진과_api는_같은_backend_망에_있다() throws IOException {
        assertThat((List<String>) services().get("engine").get("networks")).contains("backend");
        assertThat((List<String>) services().get("api").get("networks")).contains("backend");
    }

    @Test
    void env_example은_세_이름을_비워_두거나_주석으로_둔다() throws IOException {
        String example = Files.readString(Path.of(".env.example"));

        assertThat(example).contains("ANTLAB_ENGINE_TOKEN=\n").contains("# ANTLAB_ENGINE_PATH_SOURCE=java\n")
                .contains("# ANTLAB_ENGINE_URL=http://127.0.0.1:8000\n");
    }
}
