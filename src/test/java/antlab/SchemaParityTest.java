package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 실습 DDL의 [스키마] 구간과 Flyway 리소스가 글자 단위로 같은지 검사한다. */
class SchemaParityTest {

    private static final List<SchemaFile> FILES = List.of(
            new SchemaFile(Path.of("db/01_schema_history.sql"),
                    Path.of("src/main/resources/db/migration/V1__schema_history.sql")),
            new SchemaFile(Path.of("db/02_schema_season.sql"),
                    Path.of("src/main/resources/db/migration/V2__schema_season.sql")),
            new SchemaFile(Path.of("db/03_schema_account.sql"),
                    Path.of("src/main/resources/db/migration/V3__schema_account.sql")));

    @Test
    void 세_마이그레이션은_원본_스키마_구간과_같다() throws IOException {
        for (SchemaFile file : FILES) {
            String expected = schemaSection(Files.readString(file.source(), StandardCharsets.UTF_8));
            String actual = Files.readString(file.migration(), StandardCharsets.UTF_8);
            assertThat(actual.equals(expected)).as(
                    "%s (최초 차이 위치=%d, 기대 길이=%d, 실제 길이=%d)",
                    file.migration(), firstDifference(expected, actual), expected.length(), actual.length())
                    .isTrue();
        }
    }

    /** An applied version is never edited; change it with a new file. V4 onward has no db/ counterpart. */
    private static final Map<String, String> PINNED_SHA256 = Map.of(
            "V1__schema_history.sql", "b0cb1398ec6d7ab1d3e387fbb3359eafc5d6faefd71db3a0f30b55261566d9cf",
            "V2__schema_season.sql", "b7ccc19afbcd81525711a424454d64895c6f6a41c0669aa9d44e9f780874ef8d",
            "V3__schema_account.sql", "3fe8c700cd613bc0b3f5eba6e4a9524e8a9839da48da64cc580f851d5b55aa05",
            "V4__auth.sql", "3b1552c91557615f39cb4037fc4b19995fd79ac03fe4b8d63155a72bc43ec1f8");

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    void 모든_마이그레이션_내용이_고정되어_있다() throws Exception {
        for (Map.Entry<String, String> pin : PINNED_SHA256.entrySet()) {
            byte[] bytes = Files.readAllBytes(MIGRATION_DIR.resolve(pin.getKey()));
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(actual.equals(pin.getValue()))
                    .as("%s: 적용된 버전은 고치지 않는다 — 변경은 다음 번호의 새 파일로 한다", pin.getKey())
                    .isTrue();
        }
    }

    @Test
    void 마이그레이션_파일마다_고정값이_있다() throws IOException {
        try (var files = Files.list(MIGRATION_DIR)) {
            assertThat(files.map(f -> f.getFileName().toString()).toList())
                    .as("고정값 없는 마이그레이션이 있으면 안 된다 — 새 파일은 PINNED_SHA256 에 함께 넣는다")
                    .containsExactlyInAnyOrderElementsOf(PINNED_SHA256.keySet());
        }
    }

    @Test
    void 표식은_시작과_종료가_각각_하나여야_한다() {
        assertThatThrownBy(() -> schemaSection("-- [스키마]\nCREATE TABLE x (id int);\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schemaSection("-- [스키마]\n-- [/스키마]\n-- [/스키마]\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schemaSection("-- [/스키마]\n-- [스키마]\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 공백_주석_개행_한_글자_차이는_검출된다() {
        String source = "-- [스키마] 설명\n-- 원본 주석\nCREATE TABLE x (id int);\n-- [/스키마]\n";
        assertThat(schemaSection(source)).isEqualTo("-- 원본 주석\nCREATE TABLE x (id int);\n");
        String section = schemaSection(source);
        assertThat(firstDifference(section, section + "\n")).isEqualTo(section.length());
        assertThat(firstDifference(section, "-- 원본 주석 변경\nCREATE TABLE x (id int);\n")).isEqualTo(8);
        assertThat(firstDifference(section, section)).isEqualTo(-1);

        String crlfSource = source.replace("\n", "\r\n");
        assertThat(schemaSection(crlfSource)).isNotEqualTo(schemaSection(source));
    }

    static String schemaSection(String source) {
        List<Integer> starts = markerLineStarts(source, START_MARKER);
        List<Integer> ends = markerLineStarts(source, END_MARKER);

        if (starts.size() != 1 || ends.size() != 1 || starts.get(0) >= ends.get(0)) {
            throw new IllegalArgumentException("[스키마] 표식이 정확히 하나의 올바른 구간이어야 합니다.");
        }

        int contentStart = lineEnd(source, starts.get(0));
        if (contentStart > ends.get(0)) {
            throw new IllegalArgumentException("[스키마] 표식이 정확히 하나의 올바른 구간이어야 합니다.");
        }
        return source.substring(contentStart, ends.get(0));
    }

    private static final String START_MARKER = "-- [스키마]";
    private static final String END_MARKER = "-- [/스키마]";
    private static final Pattern START_LINE = Pattern.compile(
            "(?m)^-- \\[스키마\\][^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern END_LINE = Pattern.compile(
            "(?m)^-- \\[\\/스키마\\][^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");

    private static List<Integer> markerLineStarts(String source, String marker) {
        Pattern pattern = START_MARKER.equals(marker) ? START_LINE : END_LINE;
        Matcher matcher = pattern.matcher(source);
        List<Integer> positions = new java.util.ArrayList<>();
        while (matcher.find()) {
            positions.add(matcher.start());
        }
        return List.copyOf(positions);
    }

    private static int lineEnd(String source, int lineStart) {
        int lf = source.indexOf('\n', lineStart);
        int cr = source.indexOf('\r', lineStart);
        int newline;
        if (lf < 0) {
            newline = cr;
        } else if (cr < 0) {
            newline = lf;
        } else {
            newline = Math.min(lf, cr);
        }
        if (newline < 0) {
            return source.length();
        }
        if (source.charAt(newline) == '\r' && newline + 1 < source.length()
                && source.charAt(newline + 1) == '\n') {
            return newline + 2;
        }
        return newline + 1;
    }

    private static int firstDifference(String expected, String actual) {
        int commonLength = Math.min(expected.length(), actual.length());
        for (int i = 0; i < commonLength; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return i;
            }
        }
        return expected.length() == actual.length() ? -1 : commonLength;
    }

    private record SchemaFile(Path source, Path migration) {
    }
}
