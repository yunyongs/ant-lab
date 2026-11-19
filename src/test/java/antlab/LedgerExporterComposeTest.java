package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Static checks of the ledger exporter service (OBS-08): read-only single-file mounts, no route out, no journal, least privilege. */
class LedgerExporterComposeTest {

    private static final Path DOCKERFILE = Path.of("ai/Dockerfile.ledger-exporter");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of(file)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        return (Map<String, Map<String, Object>>) load("compose.yaml").get("services");
    }

    private static Map<String, Object> exporter() throws IOException {
        return services().get("ledger-exporter");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mounts() throws IOException {
        return (List<Map<String, Object>>) exporter().get("volumes");
    }

    @Test
    void 장부_내보내기는_monitoring_프로필에만_있고_포트를_게시하지_않는다() throws IOException {
        var exporter = exporter();
        assertThat(exporter.get("profiles")).isEqualTo(List.of("monitoring"));
        assertThat(exporter).doesNotContainKeys("ports", "expose", "env_file", "network_mode", "extra_hosts", "dns");
        assertThat(exporter.toString()).doesNotContain("9190:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 장부_내보내기는_바깥으로_나가는_길이_없는_내부_네트워크에만_붙는다() throws IOException {
        assertThat(exporter().get("networks")).isEqualTo(List.of("ledger"));
        var networks = (Map<String, Map<String, Object>>) load("compose.yaml").get("networks");
        assertThat(networks.get("ledger")).containsEntry("internal", true);
        // Only Prometheus shares the internal network; every other service stays off it.
        services().forEach((name, service) -> {
            boolean onLedger = service.get("networks") instanceof List<?> list && list.contains("ledger");
            assertThat(onLedger).as(name).isEqualTo(name.equals("ledger-exporter") || name.equals("prometheus"));
        });
    }

    @Test
    void 마운트는_세_장부뿐이고_모두_읽기_전용이며_폴더는_수집_폴더_하나다() throws IOException {
        var mounts = mounts();
        assertThat(mounts).hasSize(3);
        assertThat(mounts).extracting(m -> m.get("source")).containsExactly(
                "${ANTLAB_OPENROUTER_LEDGER:-/dev/null}", "${ANTLAB_OBS_OLLAMA_LEDGER:-/dev/null}", "${ANTLAB_OBS_FETCH_DIR:-./monitoring/ledger-exporter/empty}");
        assertThat(mounts).extracting(m -> m.get("target")).containsExactly(
                "/ledgers/openrouter.jsonl", "/ledgers/ollama.jsonl", "/ledgers/fetch");
        for (var m : mounts) {
            assertThat(m.get("type")).isEqualTo("bind");
            assertThat(m.get("read_only")).isEqualTo(true);
            assertThat(m.toString()).contains("create_host_path=false");
        }
        // The fetch tool replaces ledger.csv atomically, so its folder is mounted; the default folder is tracked and holds no ledger.
        try (var files = Files.list(Path.of("monitoring/ledger-exporter/empty"))) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).containsExactly(".gitkeep");
        }
    }

    @Test
    void 러너_저널_상태_폴더와_하네스_폴더는_마운트하지_않는다() throws IOException {
        String text = mounts().toString().toLowerCase() + exporter().get("environment").toString().toLowerCase();
        for (String forbidden : List.of("journal", "runner", "request_text", "response_text", "data/cache")) {
            assertThat(text).as(forbidden).doesNotContain(forbidden);
        }
        // The OpenRouter lock file sits beside its ledger: only the two single-file targets and the fetch folder are allowed.
        assertThat(mounts()).extracting(m -> m.get("target").toString())
                .allSatisfy(t -> assertThat(t).matches("/ledgers/(openrouter\\.jsonl|ollama\\.jsonl|fetch)"));
    }

    @Test
    void 최소_권한과_메모리_상한을_둔다() throws IOException {
        var exporter = exporter();
        assertThat(exporter.get("read_only")).isEqualTo(true);
        assertThat(exporter.get("cap_drop")).isEqualTo(List.of("ALL"));
        assertThat(exporter.get("security_opt")).isEqualTo(List.of("no-new-privileges:true"));
        assertThat(exporter.get("mem_limit")).isEqualTo("64m");
        assertThat(exporter.get("user").toString()).matches("\\$\\{ANTLAB_OBS_UID:-[1-9]\\d*}:\\$\\{ANTLAB_OBS_GID:-[1-9]\\d*}");
        assertThat(exporter.get("restart")).isEqualTo("unless-stopped");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 환경에_비밀값_이름이_없고_경로는_컨테이너_안_고정_경로다() throws IOException {
        var env = (Map<String, Object>) exporter().get("environment");
        assertThat(env.keySet()).noneMatch(k -> k.matches(".*(KEY|TOKEN|PASSWORD|SECRET).*"));
        assertThat(env.get("LEDGER_OPENROUTER_PATH")).isEqualTo("/ledgers/openrouter.jsonl");
        assertThat(env.get("LEDGER_OLLAMA_PATH")).isEqualTo("/ledgers/ollama.jsonl");
        assertThat(env.get("LEDGER_FETCH_PATH")).isEqualTo("/ledgers/fetch/ledger.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 상한과_정지선은_compose에_기본값_복사본이_없다() throws IOException {
        var env = (Map<String, Object>) exporter().get("environment");
        for (String name : List.of("ANTLAB_OBS_FETCH_CAP_KIS", "ANTLAB_OBS_FETCH_CAP_YF", "ANTLAB_OBS_OLLAMA_STOP_USD")) {
            assertThat(env.get(name)).isEqualTo("${" + name + ":-}");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 이미지는_내보내기_패키지만_담고_비루트로_돈다() throws IOException {
        var build = (Map<String, Object>) exporter().get("build");
        assertThat(build).containsEntry("context", "./ai").containsEntry("dockerfile", "Dockerfile.ledger-exporter");
        String docker = Files.readString(DOCKERFILE);
        assertThat(docker).contains("USER 10002:10002").contains("COPY src/antlab_ai/ledger_exporter");
        assertThat(docker).doesNotContain("pip install").doesNotContain("openrouter").doesNotContain("USER root");
        String ignore = Files.readString(Path.of("ai/Dockerfile.ledger-exporter.dockerignore"));
        assertThat(ignore.lines().filter(l -> !l.startsWith("#") && !l.isBlank())).containsExactly(
                "*", "!src/antlab_ai/__init__.py", "!src/antlab_ai/ledger_exporter/", "**/__pycache__");
    }

    @Test
    void 내보내기_소스는_쓰기_모드와_네트워크_클라이언트를_쓰지_않는다() throws IOException {
        String source = Files.readString(Path.of("ai/src/antlab_ai/ledger_exporter/exporter.py"));
        for (String forbidden : List.of("O_WRONLY", "O_RDWR", "O_CREAT", "O_APPEND", "urllib", "import requests", "httpx", "socket", "subprocess", "environ")) {
            assertThat(source).as(forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void prometheus_잡은_내부_주소로_긁고_env_이름은_예시_파일에_있다() throws IOException {
        var jobs = (List<Map<String, Object>>) load("monitoring/prometheus/prometheus.yml").get("scrape_configs");
        var job = jobs.stream().filter(j -> "antlab-ledgers".equals(j.get("job_name"))).findFirst().orElseThrow();
        assertThat(((List<Map<String, Object>>) job.get("static_configs")).get(0).get("targets")).isEqualTo(List.of("ledger-exporter:9190"));
        assertThat(job).doesNotContainKeys("basic_auth", "authorization", "params");
        String env = Files.readString(Path.of(".env.example"));
        for (String name : List.of("ANTLAB_OPENROUTER_LEDGER", "ANTLAB_OBS_OLLAMA_LEDGER", "ANTLAB_OBS_FETCH_DIR", "ANTLAB_OBS_UID", "ANTLAB_OBS_GID")) {
            assertThat(env).contains("# " + name + "=");
        }
    }
}
