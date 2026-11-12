package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 시험 도우미: SQL 정의 {@code sr_report} 의 {@code RETURNS TABLE} 열 이름(snake_case, 선언 순서)과 camelCase 변환. */
public final class SeasonReportColumns {

    static final Path DEFINITION = Path.of("db/26_season_report_def.sql");

    private SeasonReportColumns() {
    }

    public static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }

    public static List<String> sqlColumns() {
        try {
            String sql = Files.readString(DEFINITION);
            Matcher m = Pattern.compile("FUNCTION pg_temp\\.sr_report\\(p_season_id bigint\\)\\s*RETURNS TABLE \\((.*?)\\)\\s*LANGUAGE",
                    Pattern.DOTALL).matcher(sql);
            assertThat(m.find()).as("sr_report RETURNS TABLE 절").isTrue();
            List<String> names = new ArrayList<>();
            for (String column : m.group(1).split(",")) {
                String trimmed = column.strip();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed.split("\\s+")[0]);
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
