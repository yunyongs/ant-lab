package antlab.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 열 코멘트의 노출 태그({@code [노출]}·{@code [복기 전용]}·{@code [내부]})를 읽는다.
 * 키는 {@code 표.열}. 응답 record 가 어느 열에서 오는지를 이 태그로 대조한다(SPRING_JPA §4.6).
 */
public record ExposureCatalog(Map<String, String> tags) {

    public static final String EXPOSED = "노출";
    public static final String REVEAL_ONLY = "복기 전용";
    public static final String INTERNAL = "내부";

    private static final Pattern TAG = Pattern.compile("^\\[(노출|복기 전용|내부)]");

    /** public 스키마의 모든 표·열의 태그. 태그가 없는 열은 값이 null 로 들어간다. */
    public static ExposureCatalog load(JdbcTemplate jdbc) {
        Map<String, String> tags = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.relname, a.attname, col_description(c.oid, a.attnum)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
                  AND c.relname <> 'flyway_schema_history'
                ORDER BY c.relname, a.attnum
                """, rs -> {
            Matcher m = TAG.matcher(rs.getString(3) == null ? "" : rs.getString(3));
            tags.put(rs.getString(1) + "." + rs.getString(2), m.find() ? m.group(1) : null);
        });
        return new ExposureCatalog(tags);
    }

    public boolean has(String column) {
        return tags.containsKey(column);
    }

    /** 태그 이름 또는 null(열이 없거나 태그가 없음). */
    public String tag(String column) {
        return tags.get(column);
    }
}
