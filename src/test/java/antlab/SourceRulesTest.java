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
    private static final Pattern ROUNDING = Pattern.compile("\\b(setScale|RoundingMode|MathContext|ROUND_[A-Z_]+)\\b");
    /** No identifier char before Math, so MoneyMath.round8 does not match. */
    private static final Pattern MATH_ROUND = Pattern.compile("(?<![\\w$])(?:Strict)?Math\\.round\\b");
    /** Static import of Math.round or Math.*; call sites then show no Math prefix. */
    private static final Pattern MATH_STATIC_IMPORT = Pattern
            .compile("\\bimport\\s+static\\s+java\\.lang\\.(?:Strict)?Math\\s*\\.\\s*(?:round|\\*)\\s*;");
    /** Removed before checking: only the non-rounding UNLIMITED context (and its import) is allowed. */
    private static final Pattern MATH_CONTEXT_UNLIMITED = Pattern
            .compile("\\bMathContext\\s*\\.\\s*UNLIMITED\\b|\\bimport\\s+java\\.math\\.MathContext\\s*;");
    private static final Pattern DIVIDE_CALL = Pattern.compile("\\bdivide\\s*\\(");
    /** The only source path allowed to round, relative to the repository root. */
    private static final Path ROUNDING_ALLOWED = Path.of("src/main/java/antlab/common/money/MoneyMath.java");

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

    /** Blanks comments and literal contents (keeps newlines and quotes) so prose cannot trigger violations. */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (; i < stop; i++) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (source.startsWith("\"\"\"", i)) {
                i = skipLiteral(source, i + 3, "\"\"\"", out, "\"\"\"");
            } else if (c == '"' || c == '\'') {
                i = skipLiteral(source, i + 1, String.valueOf(c), out, String.valueOf(c));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Skips to the closing delimiter honoring escapes; returns the next index to read. */
    private static int skipLiteral(String s, int from, String close, StringBuilder out, String open) {
        out.append(open);
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '\\') {
                i += 2;
            } else if (s.startsWith(close, i)) {
                out.append(close);
                return i + close.length();
            } else {
                if (s.charAt(i) == '\n') {
                    out.append('\n');
                }
                i++;
            }
        }
        return i;
    }

    /** True if any divide call has two or more arguments; every such overload takes a rounding mode or MathContext. */
    static boolean hasRoundingDivide(String code) {
        var m = DIVIDE_CALL.matcher(code);
        while (m.find()) {
            int depth = 1;
            int commas = 0;
            boolean hasArg = false;
            for (int i = m.end(); i < code.length() && depth > 0; i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 1) {
                    commas++;
                }
                if (depth > 0 && !Character.isWhitespace(c) && c != ',') {
                    hasArg = true;
                }
            }
            if (hasArg && commas >= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forbidden outside MoneyMath: setScale, RoundingMode, MathContext (UNLIMITED excepted; DECIMAL32/64/128
     * are finite precision and round), BigDecimal.ROUND_*, Math.round incl. static import, divide with 2+ args.
     * Comments and literals are ignored.
     */
    static boolean hasRoundingToken(String source) {
        String code = stripCommentsAndLiterals(source);
        String withoutUnlimited = MATH_CONTEXT_UNLIMITED.matcher(code).replaceAll(" ");
        return ROUNDING.matcher(withoutUnlimited).find()
                || MATH_ROUND.matcher(code).find()
                || MATH_STATIC_IMPORT.matcher(code).find()
                || hasRoundingDivide(code);
    }

    /** Exact match on the normalized root-relative path; same-named files in other packages are rejected. */
    static boolean isRoundingAllowedPath(Path file) {
        return file.normalize().equals(ROUNDING_ALLOWED);
    }

    static boolean violatesRoundingRule(Path file, String source) {
        return !isRoundingAllowedPath(file) && hasRoundingToken(source);
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
        assertThat(files).contains(ROUNDING_ALLOWED);
        for (Path file : files) {
            assertThat(violatesRoundingRule(file, Files.readString(file))).as(file.toString()).isFalse();
        }
    }

    @Test
    void 반올림_검사기는_금지_토큰을_잡는다() {
        assertThat(hasRoundingToken("value.setScale(0, RoundingMode.HALF_UP)")).isTrue();
        assertThat(hasRoundingToken("java.math.RoundingMode.HALF_UP")).isTrue();
        assertThat(hasRoundingToken("import static java.math.RoundingMode.*;")).isTrue();
        assertThat(hasRoundingToken("a.round(new MathContext(5))")).isTrue();
        assertThat(hasRoundingToken("a.divide(b, MathContext.DECIMAL128)")).isTrue();
        assertThat(hasRoundingToken("java.math.MathContext mc = null;")).isTrue();
        assertThat(hasRoundingToken("long r = Math.round(x);")).isTrue();
        assertThat(hasRoundingToken("long r = java.lang.Math.round(x);")).isTrue();
        assertThat(hasRoundingToken("import static java.lang.Math.round;")).isTrue();
        assertThat(hasRoundingToken("import static java.lang.Math.*;")).isTrue();
        assertThat(hasRoundingToken("a.setScale(2, BigDecimal.ROUND_HALF_UP)")).isTrue();
        assertThat(hasRoundingToken("import static java.math.BigDecimal.ROUND_DOWN;")).isTrue();
        assertThat(hasRoundingToken("a.divide(b, 4, 4)")).isTrue();
        assertThat(hasRoundingToken("a.divide(b, 4)")).isTrue();
        assertThat(hasRoundingToken("a.divide(f(b, c), 4, mode)")).isTrue();
        assertThat(hasRoundingToken("a.divide(\n  b,\n  4,\n  mode)")).isTrue();
    }

    @Test
    void 반올림_검사기는_정상_사용을_오탐하지_않는다() {
        assertThat(hasRoundingToken("value.add(other)")).isFalse();
        assertThat(hasRoundingToken("MoneyMath.round8(x)")).isFalse();
        assertThat(hasRoundingToken("antlab.common.money.MoneyMath.round8(x)")).isFalse();
        assertThat(hasRoundingToken("import static antlab.common.money.MoneyMath.round8;")).isFalse();
        assertThat(hasRoundingToken("MoneyMath.pad40Divide(a, b)")).isFalse();
        assertThat(hasRoundingToken("a.divide(b)")).isFalse();
        assertThat(hasRoundingToken("a.divide(f(b, c))")).isFalse();
        assertThat(hasRoundingToken("a.divide(BigDecimal.valueOf(2))")).isFalse();
        assertThat(hasRoundingToken("a.multiply(b, MathContext.UNLIMITED)")).isFalse();
        assertThat(hasRoundingToken("import java.math.MathContext;\nvar mc = MathContext.UNLIMITED;")).isFalse();
        assertThat(hasRoundingToken("int r = Math.max(a, b);")).isFalse();
        assertThat(hasRoundingToken("int roundTrip = ROUNDING_STEP;")).isFalse();
        assertThat(hasRoundingToken("// setScale 는 MoneyMath 에서만\nint x;")).isFalse();
        assertThat(hasRoundingToken("/** RoundingMode, Math.round 금지\n * divide(a, 2, 4) */ int x;")).isFalse();
        assertThat(hasRoundingToken("String s = \"setScale RoundingMode \\\" Math.round(x)\";")).isFalse();
        assertThat(hasRoundingToken("char c = '\"'; int y;")).isFalse();
        assertThat(hasRoundingToken("String t = \"\"\"\n  a.divide(b, 4, 4)\n  \"\"\";")).isFalse();
    }

    @Test
    void 주석을_지워도_코드의_위반은_남는다() {
        assertThat(hasRoundingToken("/* ok */ x.setScale(0, RoundingMode.UP);")).isTrue();
        assertThat(hasRoundingToken("String s = \"a\"; x.setScale(0);")).isTrue();
        assertThat(hasRoundingToken("// c\nx.setScale(0);")).isTrue();
        assertThat(stripCommentsAndLiterals("a /* b\nc */ d // e\nf \"g\" h")).isEqualTo("a     \n     d \nf \"\" h");
    }

    @Test
    void 경로_판정은_허용_경로_한_곳만_통과시킨다() {
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/common/money/MoneyMath.java"))).isTrue();
        assertThat(isRoundingAllowedPath(Path.of("./src/main/java/antlab/common/money/MoneyMath.java"))).isTrue();
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/common/../common/money/MoneyMath.java"))).isTrue();
        // same file name, other package
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/scoring/MoneyMath.java"))).isFalse();
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/common/MoneyMath.java"))).isFalse();
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/other/common/money/MoneyMath.java"))).isFalse();
        // similar name, other source tree, case, absolute path
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/common/money/MoneyMathHelper.java"))).isFalse();
        assertThat(isRoundingAllowedPath(Path.of("src/test/java/antlab/common/money/MoneyMath.java"))).isFalse();
        assertThat(isRoundingAllowedPath(Path.of("src/main/java/antlab/common/money/moneymath.java"))).isFalse();
        assertThat(isRoundingAllowedPath(Path.of("/abs/src/main/java/antlab/common/money/MoneyMath.java"))).isFalse();
    }

    @Test
    void 경로와_토큰_판정은_함께_적용된다() {
        Path allowed = Path.of("src/main/java/antlab/common/money/MoneyMath.java");
        Path sameName = Path.of("src/main/java/antlab/scoring/MoneyMath.java");
        Path other = Path.of("src/main/java/antlab/scoring/PositionMeter.java");
        String bad = "x.setScale(0, RoundingMode.HALF_UP)";
        assertThat(violatesRoundingRule(allowed, bad)).isFalse();
        assertThat(violatesRoundingRule(sameName, bad)).isTrue();
        assertThat(violatesRoundingRule(other, bad)).isTrue();
        // calling MoneyMath by qualified name or static import is never a violation
        assertThat(violatesRoundingRule(other, "antlab.common.money.MoneyMath.round8(x)")).isFalse();
        assertThat(violatesRoundingRule(other, "import static antlab.common.money.MoneyMath.ceilCount;\nceilCount(a, 3);"))
                .isFalse();
        // rounding through a same-named class is still caught by tokens
        assertThat(violatesRoundingRule(other, "antlab.scoring.MoneyMath.roundOnce(x).setScale(0, java.math.RoundingMode.UP)"))
                .isTrue();
    }

    private static List<Path> listFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }
}
