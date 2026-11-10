package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 소스 규칙: 금액에 double/float 금지, 설정 파일에 비밀값 금지. 검사기 자체를 먼저 음성 사례로 확인한다. */
class SourceRulesTest {

    private static final Pattern FLOATING = Pattern.compile("\\b(double|float|Double|Float)\\b");
    private static final Pattern SECRET_KEY = Pattern.compile("^\\s*([\\w.-]*(password|secret|token|key))\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUNDING = Pattern.compile("\\b(setScale|RoundingMode)\\b");

    static boolean hasFloatingPoint(String source) {
        return FLOATING.matcher(source).find();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_.-]+)(?::(.*))?}$");

    /** 비밀 이름 키의 값이 리터럴이거나 자리표시자의 기본값이 리터럴이면 위반(빈 기본값·다른 자리표시자만 허용) */
    static boolean hasLiteralSecret(String yaml) {
        return yaml.lines().anyMatch(line -> {
            var m = SECRET_KEY.matcher(line);
            if (!m.matches()) {
                return false;
            }
            String value = m.group(3).trim().replaceAll("^['\"]|['\"]$", "");
            if (value.isEmpty()) {
                return false;
            }
            var p = PLACEHOLDER.matcher(value);
            if (!p.matches()) {
                return true;
            }
            String fallback = p.group(2);
            return fallback != null && !fallback.isEmpty() && !fallback.startsWith("${");
        });
    }

    @Test
    void 검사기는_위반_사례를_잡는다() {
        assertThat(hasFloatingPoint("double x = 1.0;")).isTrue();
        assertThat(hasFloatingPoint("Float y;")).isTrue();
        assertThat(hasFloatingPoint("BigDecimal x; doubled")).isFalse();
        assertThat(hasLiteralSecret("  password: hunter2")).isTrue();
        assertThat(hasLiteralSecret("  api-key: abc")).isTrue();
        assertThat(hasLiteralSecret("  password: ${ANTLAB_DB_PASSWORD:}")).isFalse();
        assertThat(hasLiteralSecret("  password:")).isFalse();
        assertThat(hasLiteralSecret("  password: ${ANTLAB_DB_PASSWORD:example-secret}")).isTrue();
        assertThat(hasLiteralSecret("  password: \"hunter2\"")).isTrue();
        assertThat(hasLiteralSecret("  password: \"${ANTLAB_DB_PASSWORD:}\"")).isFalse();
        assertThat(hasLiteralSecret("  password: ${A:${B:}}")).isFalse();
    }

    @Test
    void main_자바_소스에_double_float이_없다() throws IOException {
        List<Path> files = listFiles(Path.of("src/main/java"), ".java");
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            assertThat(hasFloatingPoint(Files.readString(file))).as(file.toString()).isFalse();
        }
    }

    @Test
    void application_yml에_비밀값이_없다() throws IOException {
        List<Path> files = listFiles(Path.of("src/main/resources"), ".yml");
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            assertThat(hasLiteralSecret(Files.readString(file))).as(file.toString()).isFalse();
        }
    }

    @Test
    void 반올림_구현은_MoneyMath에만_있다() throws IOException {
        List<Path> files = listFiles(Path.of("src/main"), ".java");
        Path allowed = Path.of("src/main/java/antlab/common/money/MoneyMath.java");
        for (Path file : files) {
            if (!file.equals(allowed)) {
                assertThat(ROUNDING.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
            }
        }
    }

    @Test
    void 반올림_검사기는_허용_경로와_금지_토큰을_구분한다() {
        assertThat(ROUNDING.matcher("value.setScale(0, RoundingMode.HALF_UP)").find()).isTrue();
        assertThat(ROUNDING.matcher("value.add(other)").find()).isFalse();
        assertThat(ROUNDING.matcher("java.math.RoundingMode.HALF_UP").find()).isTrue();
        assertThat(ROUNDING.matcher("import static java.math.RoundingMode.*;").find()).isTrue();
    }

    private static List<Path> listFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }
}
