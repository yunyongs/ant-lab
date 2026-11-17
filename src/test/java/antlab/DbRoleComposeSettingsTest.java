package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Static checks of how compose wires the owner and app roles (MVP-20). No Spring context, no Docker. */
class DbRoleComposeSettingsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yaml")));
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(String service) throws IOException {
        return (Map<String, Object>) services().get(service).get("environment");
    }

    @Test
    void theApiUsesTheFixedRoleNamesAsLiterals() throws IOException {
        Map<String, Object> env = environment("api");

        assertThat(env.get("ANTLAB_DB_USER")).isEqualTo("antlab_app");
        assertThat(env.get("ANTLAB_DB_OWNER_USER")).isEqualTo("antlab_owner");
        assertThat(env.get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
    }

    @Test
    void noApiVariableCanBeOverriddenFromTheHostToTheSuperuser() throws IOException {
        environment("api").forEach((name, value) -> {
            if (name.contains("DB_USER") || name.contains("DB_OWNER")) {
                assertThat(String.valueOf(value)).as(name).doesNotContain("${");
            }
        });
    }

    @Test
    void noDatabasePasswordHasAValueInCompose() throws IOException {
        for (String service : List.of("api", "db", "engine")) {
            environment(service).forEach((name, value) -> {
                if (name.toUpperCase().contains("PASSWORD")) {
                    assertThat(String.valueOf(value)).as(service + " " + name).matches("\\$\\{[A-Z_]+:-}|null");
                }
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDbMountsTheRoleScriptsReadOnly() throws IOException {
        List<String> volumes = (List<String>) services().get("db").get("volumes");

        assertThat(volumes).contains("./db/roles:/antlab-roles:ro", "./db/roles/initdb:/docker-entrypoint-initdb.d:ro");
        assertThat(volumes.stream().filter(v -> v.contains("/db/roles") || v.startsWith("./db/roles"))).allMatch(v -> v.endsWith(":ro"));
    }

    @Test
    void theEngineServiceIsUntouched() throws IOException {
        Map<String, Object> env = environment("engine");

        assertThat(env.get("ANTLAB_ENGINE_DB_USER")).isEqualTo("antlab_engine");
        assertThat(services().get("engine")).doesNotContainKey("volumes");
    }

    @Test
    void theInitdbWrapperIsTheOnlyFileInItsDirectory() throws IOException {
        try (var files = Files.list(Path.of("db/roles/initdb"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("10-antlab-roles.sh");
        }
    }
}
