package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Static guards for the client package: the engine's own sentence and the secrets never reach a log call. */
class EngineClientSourceRulesTest {

    private static final Path DIR = Path.of("src/main/java/antlab/engine/client");
    private static final Pattern DETAIL_READ = Pattern.compile("\\.detail\\(\\)|getDetail\\(");
    private static final Pattern LOG_CALL = Pattern.compile("(?m)^\\s*LOG\\.\\w+\\((.*)$");
    private static final Pattern SENSITIVE = Pattern.compile("\\b(token|body|seed|seasonSeed|detail|months|request)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDIRECT_OR_PROXY = Pattern.compile("Redirect\\.(NORMAL|ALWAYS)|ProxySelector\\.getDefault");

    static boolean readsDetail(String source) {
        return DETAIL_READ.matcher(source).find();
    }

    static boolean logsSensitive(String source) {
        var matcher = LOG_CALL.matcher(source);
        while (matcher.find()) {
            // the quoted format text is allowed to say "operation"; only the argument expressions are checked
            String arguments = matcher.group(1).replaceAll("\"[^\"]*\"", "\"\"");
            if (SENSITIVE.matcher(arguments).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void 검사기는_위반_사례를_잡는다() {
        assertThat(readsDetail("String s = problem.detail();")).isTrue();
        assertThat(readsDetail("String s = problem.title();")).isFalse();
        assertThat(logsSensitive("LOG.warn(\"x {}\", token);")).isTrue();
        assertThat(logsSensitive("LOG.error(\"x {}\", problem.detail());")).isTrue();
        assertThat(logsSensitive("LOG.error(\"engine.bad_response operation={} status={}\", operation, httpStatus);")).isFalse();
    }

    @Test
    void 엔진의_detail_은_어디서도_읽지_않고_로그_인자에_민감한_이름이_없다() throws IOException {
        List<Path> files = sources();
        assertThat(files).hasSizeGreaterThan(8);
        for (Path file : files) {
            String source = Files.readString(file);
            assertThat(readsDetail(source)).as(file + " reads the engine detail").isFalse();
            assertThat(logsSensitive(source)).as(file + " logs a sensitive value").isFalse();
        }
    }

    @Test
    void 리다이렉트를_따르거나_시스템_프록시를_고르는_코드가_없다() throws IOException {
        for (Path file : sources()) {
            assertThat(REDIRECT_OR_PROXY.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
        }
    }
}
