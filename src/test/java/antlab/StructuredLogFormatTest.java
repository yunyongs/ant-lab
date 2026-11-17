package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prod logging properties (MVP-20 PR B) as a real process: a tiny Boot application with no DB and no web server is
 * started in a child JVM with the real application.yml, and its stdout is read byte for byte. A child process keeps the
 * logging system of the test JVM untouched, so no later test sees JSON.
 */
class StructuredLogFormatTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MARKER = "structured-log-marker-8f3a";
    // Quotes, a backslash and a newline break a text grep once the line is JSON. A Base64 value (+, /, =) must stay
    // a plain substring, because monitoring_smoke.sh looks for the signing key in the raw log text.
    private static final String BASE64_LIKE = "ab/cd+ef/gh=";
    private static final String QUOTED = "he said \"hi\" \\ " + BASE64_LIKE + " and\nleft " + MARKER;

    /** The child application: starts the context, writes two events and exits. */
    public static final class Child {
        public static void main(String[] args) {
            new SpringApplicationBuilder(ChildConfig.class).web(WebApplicationType.NONE).run(args).close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ChildConfig {
        ChildConfig() {
            Logger log = LoggerFactory.getLogger("antlab.test.StructuredLogChild");
            log.info(MARKER + " info");
            log.warn(QUOTED, new IllegalStateException("boom-" + MARKER));
        }
    }

    private record Output(int exit, List<String> lines) {}

    private static Output runChild(String... systemProperties) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java", "-Dspring.config.location=optional:classpath:/"));
        command.addAll(List.of(systemProperties));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Child.class.getName()));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        // The caller's shell may export ANTLAB_* or SPRING_* variables; the child reads tracked config only.
        pb.environment().keySet().removeIf(k -> k.startsWith("ANTLAB_") || k.startsWith("SPRING_"));
        Process process = pb.start();
        byte[] bytes = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("child JVM finished").isTrue();
        String text = new String(bytes, StandardCharsets.UTF_8);
        return new Output(process.exitValue(), text.lines().filter(l -> !l.isBlank()).toList());
    }

    private static JsonNode path(JsonNode node, String dotted) {
        JsonNode current = node;
        for (String part : dotted.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    /** ECS nests the fields (log.level) or flattens them ("log.level"); accept the dotted path either way. */
    private static String text(JsonNode line, String dotted) {
        JsonNode nested = path(line, dotted);
        if (!nested.isMissingNode()) {
            return nested.asString();
        }
        return line.path(dotted).asString("");
    }

    @Test
    void prod_writes_only_ecs_json_lines() throws Exception {
        Output out = runChild("-Dspring.profiles.active=prod");
        assertThat(out.exit()).as(String.join("\n", out.lines())).isZero();
        assertThat(out.lines()).isNotEmpty();
        List<JsonNode> events = new ArrayList<>();
        for (String line : out.lines()) {
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (RuntimeException e) {
                throw new AssertionError("not a JSON line: " + line, e);
            }
            assertThat(node.isObject()).as(line).isTrue();
            events.add(node);
        }
        for (JsonNode event : events) {
            assertThat(event.has("@timestamp")).as(event.toString()).isTrue();
            assertThat(text(event, "log.level")).as(event.toString()).isNotEmpty();
            assertThat(text(event, "log.logger")).as(event.toString()).isNotEmpty();
            assertThat(event.has("message")).as(event.toString()).isTrue();
            assertThat(text(event, "service.name")).as(event.toString()).isEqualTo("antlab-api");
        }
        List<JsonNode> info = events.stream().filter(e -> e.path("message").asString("").equals(MARKER + " info")).toList();
        assertThat(info).hasSize(1);
        assertThat(text(info.get(0), "log.level")).isEqualTo("INFO");
        assertThat(text(info.get(0), "log.logger")).isEqualTo("antlab.test.StructuredLogChild");

        // The quoted text round-trips through JSON escaping; a raw grep for the quotes would not find it.
        List<JsonNode> warn = events.stream().filter(e -> e.path("message").asString("").equals(QUOTED)).toList();
        assertThat(warn).hasSize(1);
        assertThat(text(warn.get(0), "log.level")).isEqualTo("WARN");
        assertThat(out.lines()).as("Base64 characters are not escaped").anyMatch(l -> l.contains(BASE64_LIKE));
        assertThat(text(warn.get(0), "error.type")).isEqualTo(IllegalStateException.class.getName());
        assertThat(text(warn.get(0), "error.message")).isEqualTo("boom-" + MARKER);
        assertThat(text(warn.get(0), "error.stack_trace")).contains("IllegalStateException", "StructuredLogFormatTest");
    }

    @Test
    void prod_prints_no_banner_and_no_plain_text_line() throws Exception {
        Output out = runChild("-Dspring.profiles.active=prod");
        assertThat(out.lines()).allMatch(l -> l.startsWith("{") && l.endsWith("}"), "every line is one JSON object");
        assertThat(String.join("\n", out.lines())).doesNotContain(":: Spring Boot ::");
    }

    @Test
    void the_default_profile_stays_plain_text() throws Exception {
        Output out = runChild();
        assertThat(out.exit()).as(String.join("\n", out.lines())).isZero();
        assertThat(out.lines()).anyMatch(l -> l.contains(MARKER + " info") && !l.startsWith("{"));
        assertThat(out.lines()).noneMatch(l -> l.startsWith("{\"@timestamp\""));
    }

    @Test
    void the_local_profile_stays_plain_text() throws Exception {
        Output out = runChild("-Dspring.profiles.active=local");
        assertThat(out.lines()).anyMatch(l -> l.contains(MARKER + " info") && !l.startsWith("{"));
        assertThat(out.lines()).noneMatch(l -> l.startsWith("{\"@timestamp\""));
    }
}
