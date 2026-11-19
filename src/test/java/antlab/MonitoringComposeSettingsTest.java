package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Static checks of the compose monitoring profile, scrape config, data source provisioning and .env.example. No Spring context, no Docker. */
class MonitoringComposeSettingsTest {

    private static final String PASSWORD_VAR = "GF_SECURITY_ADMIN_PASSWORD";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of(file)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        return (Map<String, Map<String, Object>>) load("compose.yaml").get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> grafanaEnvironment() throws IOException {
        return (Map<String, Object>) services().get("grafana").get("environment");
    }

    @Test
    void 모니터링_서비스는_monitoring_프로필에만_있다() throws IOException {
        var services = services();
        assertThat(services.get("prometheus").get("profiles")).isEqualTo(List.of("monitoring"));
        assertThat(services.get("grafana").get("profiles")).isEqualTo(List.of("monitoring"));
        assertThat(services.get("db")).doesNotContainKey("profiles");
        assertThat(services.get("api").get("profiles")).isEqualTo(List.of("api"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모든_서비스의_게시_포트는_루프백뿐이다() throws IOException {
        var services = services();
        services.forEach((name, service) -> {
            for (Object port : (List<Object>) service.getOrDefault("ports", List.of())) {
                assertThat(port.toString()).as("%s port %s", name, port).startsWith("127.0.0.1:");
            }
        });
        assertThat(services.get("prometheus").get("ports")).isEqualTo(List.of("127.0.0.1:${ANTLAB_PROMETHEUS_PORT:-9090}:9090"));
        assertThat(services.get("grafana").get("ports")).isEqualTo(List.of("127.0.0.1:${ANTLAB_GRAFANA_PORT:-3000}:3000"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 어떤_서비스도_관리_포트를_게시하지_않는다() throws IOException {
        services().forEach((name, service) -> {
            for (Object port : (List<Object>) service.getOrDefault("ports", List.of())) {
                assertThat(port.toString()).as("%s port %s", name, port).doesNotContain("8081");
            }
            assertThat(service.toString()).as(name).doesNotContain("ANTLAB_MANAGEMENT_PORT");
        });
    }

    @Test
    void 모니터링_이미지는_정확한_판으로_고정한다() throws IOException {
        var services = services();
        assertThat(services.get("prometheus").get("image").toString()).matches("^prom/prometheus:v\\d+\\.\\d+\\.\\d+$");
        assertThat(services.get("grafana").get("image").toString()).matches("^grafana/grafana:\\d+\\.\\d+\\.\\d+$");
    }

    @Test
    @SuppressWarnings("unchecked")
    void Grafana_관리자_비밀번호는_기본값_없이_env에서만_온다() throws IOException {
        var grafana = services().get("grafana");
        var env = grafanaEnvironment();
        assertThat(env.get(PASSWORD_VAR).toString()).matches("^\\$\\{" + PASSWORD_VAR + ":-\\}$");
        assertThat(grafana).doesNotContainKey("env_file");
        assertThat(env.get("GF_AUTH_ANONYMOUS_ENABLED")).isEqualTo("false");
        assertThat(env.get("GF_USERS_ALLOW_SIGN_UP")).isEqualTo("false");
        // The empty default must be backed by a start-time guard, otherwise Grafana would start with its built-in admin/admin.
        List<String> entrypoint = (List<String>) grafana.get("entrypoint");
        assertThat(entrypoint).hasSize(4);
        assertThat(entrypoint.get(0)).isEqualTo("/bin/sh");
        assertThat(entrypoint.get(1)).isEqualTo("-c");
        assertThat(entrypoint.get(3)).isEqualTo("--");
        String script = entrypoint.get(2);
        assertThat(script).startsWith("test -n \"$$" + PASSWORD_VAR + "\" || {").contains("exit 1; }").endsWith("exec /run.sh \"$$@\"");
        assertThat(script.indexOf("/run.sh")).isEqualTo(script.lastIndexOf("/run.sh"));
    }

    @Test
    void grafana_메모리_상한은_512m이다() throws IOException {
        // Measured 243.6 MiB of the former 256m limit on the owner stack (2026-11-19).
        assertThat(services().get("grafana").get("mem_limit")).isEqualTo("512m");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모니터링_서비스는_backend에만_붙고_설정은_읽기_전용이다() throws IOException {
        var services = services();
        var prometheus = services.get("prometheus");
        var grafana = services.get("grafana");
        assertThat(prometheus.get("networks")).isEqualTo(List.of("backend"));
        assertThat(grafana.get("networks")).isEqualTo(List.of("backend"));
        for (var service : List.of(prometheus, grafana)) {
            List<String> volumes = (List<String>) service.get("volumes");
            assertThat(volumes.stream().filter(v -> v.startsWith("./monitoring/"))).isNotEmpty().allMatch(v -> v.endsWith(":ro"));
        }
        assertThat((Map<String, Object>) load("compose.yaml").get("volumes")).containsKeys("prometheus-data", "grafana-data");
        assertThat(prometheus).doesNotContainKey("depends_on");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 헬스체크는_ready와_health를_부른다() throws IOException {
        var services = services();
        assertThat(((Map<String, Object>) services.get("prometheus").get("healthcheck")).get("test"))
                .isEqualTo(List.of("CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:9090/-/ready"));
        assertThat(((Map<String, Object>) services.get("grafana").get("healthcheck")).get("test"))
                .isEqualTo(List.of("CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:3000/api/health"));
        var dependsOn = (Map<String, Map<String, Object>>) services.get("grafana").get("depends_on");
        assertThat(dependsOn.get("prometheus").get("condition")).isEqualTo("service_healthy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scrape_설정은_api의_관리_포트를_긁는다() throws IOException {
        var jobs = (List<Map<String, Object>>) load("monitoring/prometheus/prometheus.yml").get("scrape_configs");
        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.get("job_name")).isEqualTo("antlab-api");
        assertThat(job.get("metrics_path")).isEqualTo("/actuator/prometheus");
        var targets = (List<Map<String, Object>>) job.get("static_configs");
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).get("targets")).isEqualTo(List.of("api:8081"));
        assertThat(job).doesNotContainKeys("basic_auth", "authorization");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 데이터_소스는_고정_uid로_prometheus를_가리킨다() throws IOException {
        var sources = (List<Map<String, Object>>) load("monitoring/grafana/provisioning/datasources/prometheus.yaml").get("datasources");
        assertThat(sources).hasSize(1);
        var source = sources.get(0);
        assertThat(source.get("uid")).isEqualTo("antlab-prometheus");
        assertThat(source.get("type")).isEqualTo("prometheus");
        assertThat(source.get("url")).isEqualTo("http://prometheus:9090");
        assertThat(source.get("access")).isEqualTo("proxy");
        assertThat(source.get("isDefault")).isEqualTo(true);
        assertThat(source).doesNotContainKeys("secureJsonData", "basicAuth", "basicAuthPassword");
    }

    @Test
    void env_example은_이름만_가진다() throws IOException {
        assertThat(Files.readAllLines(Path.of(".env.example")).stream().map(String::strip).filter(l -> l.startsWith(PASSWORD_VAR + "=")))
                .containsExactly(PASSWORD_VAR + "=");
    }

    private static final String READER_PASSWORD_VAR = "GRAFANA_READER_PASSWORD";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> postgresSource() throws IOException {
        var sources = (List<Map<String, Object>>) load("monitoring/grafana/provisioning/datasources/postgres.yaml").get("datasources");
        assertThat(sources).hasSize(1);
        return sources.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void PostgreSQL_데이터_소스는_고정_uid로_읽기_역할을_가리킨다() throws IOException {
        var source = postgresSource();
        assertThat(source.get("uid")).isEqualTo("antlab-postgres");
        assertThat(source.get("type")).isEqualTo("grafana-postgresql-datasource");
        assertThat(source.get("url")).isEqualTo("db:5432");
        assertThat(source.get("user")).isEqualTo("grafana_reader");
        assertThat(source.get("access")).isEqualTo("proxy");
        assertThat(source.get("editable")).isEqualTo(false);
        assertThat(source.get("isDefault")).isEqualTo(false);
        assertThat(((Map<String, Object>) source.get("jsonData")).get("database")).isEqualTo("antlab_api");
    }

    @Test
    @SuppressWarnings("unchecked")
    void PostgreSQL_연결_수는_역할의_연결_제한_안이다() throws IOException {
        // db/roles/grafana_reader.sql sets CONNECTION LIMIT 10; the Grafana default for SQL data sources is 100.
        var json = (Map<String, Object>) postgresSource().get("jsonData");
        assertThat((Integer) json.get("maxOpenConns")).isBetween(1, 10);
        assertThat((Integer) json.get("maxIdleConns")).isBetween(1, (Integer) json.get("maxOpenConns"));
        assertThat(json.get("maxIdleConnsAuto")).isEqualTo(false);
        assertThat(Files.readString(Path.of("db/roles/grafana_reader.sql"))).contains("CONNECTION LIMIT 10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void PostgreSQL_비밀번호는_파일에_값이_없고_환경_변수로만_온다() throws IOException {
        var source = postgresSource();
        assertThat(source).doesNotContainKeys("password", "basicAuth", "basicAuthPassword", "basicAuthUser");
        var secure = (Map<String, Object>) source.get("secureJsonData");
        assertThat(secure).containsOnlyKeys("password");
        assertThat(secure.get("password")).isEqualTo("$" + READER_PASSWORD_VAR);
        assertThat((Map<String, Object>) source.get("jsonData")).doesNotContainKeys("password", "tlsClientKey");
        assertThat(Files.readAllLines(Path.of("monitoring/grafana/provisioning/datasources/postgres.yaml")))
                .filteredOn(l -> l.toLowerCase().contains("password") && !l.strip().startsWith("#"))
                .containsExactly("      password: $" + READER_PASSWORD_VAR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void grafana는_읽기_역할_비밀번호를_빈_기본값의_환경_변수로_받는다() throws IOException {
        assertThat(grafanaEnvironment().get(READER_PASSWORD_VAR).toString()).isEqualTo("${" + READER_PASSWORD_VAR + ":-}");
        assertThat(services().get("grafana").get("networks")).isEqualTo(List.of("backend"));
        assertThat(Files.readAllLines(Path.of(".env.example")).stream().map(String::strip)
                .filter(l -> l.replaceFirst("^#\\s*", "").startsWith(READER_PASSWORD_VAR + "=")))
                .containsExactly("# " + READER_PASSWORD_VAR + "=");
    }
}
