package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 엔진은 시드가 아닌 난수·시계를 쓰지 않는다. 검사기 자체를 음성 문자열로 먼저 확인한다. */
class EngineSourceRulesTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "java\\.util\\.Random|\\bSecureRandom\\b|\\bThreadLocalRandom\\b|\\bMath\\.random\\b"
                    + "|\\bSystem\\.currentTimeMillis\\b|\\bInstant\\.now\\b|\\bLocalDate\\.now\\b|\\bnew Random\\b");

    static boolean hasForbidden(String source) {
        return FORBIDDEN.matcher(source).find();
    }

    @Test
    void 검사기는_위반_사례를_잡는다() {
        assertThat(hasForbidden("new java.util.Random(1)")).isTrue();
        assertThat(hasForbidden("var r = new SecureRandom();")).isTrue();
        assertThat(hasForbidden("ThreadLocalRandom.current()")).isTrue();
        assertThat(hasForbidden("Math.random()")).isTrue();
        assertThat(hasForbidden("System.currentTimeMillis()")).isTrue();
        assertThat(hasForbidden("Instant.now()")).isTrue();
        assertThat(hasForbidden("long seed = request.seed();")).isFalse();
    }

    @Test
    void engine_패키지에_금지_API가_없다() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/antlab/engine"))) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            assertThat(hasForbidden(Files.readString(file))).as(file.toString()).isFalse();
        }
    }
}
