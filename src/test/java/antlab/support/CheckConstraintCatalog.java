package antlab.support;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 의 CHECK 제약 중 "구분값 목록" 꼴({@code ck_표_열})을 읽는다. enum 상수와 글자 단위로 대조하는 데 쓴다.
 * 정의 문자열은 {@code pg_get_constraintdef} 가 만든다. 값이 하나인 {@code IN ('USD')} 은
 * {@code = 'USD'::text} 로 바뀌어 보일 수 있어 두 꼴을 모두 읽는다.
 */
public final class CheckConstraintCatalog {

    private static final Pattern LITERAL =
            Pattern.compile("'((?:[^']|'')*)'::(?:character varying|text|bpchar)");

    private CheckConstraintCatalog() {
    }

    /** 정의 문자열에서 문자열 리터럴 값을 순서대로 뽑는다(작은따옴표 이스케이프 {@code ''} 를 푼다). */
    public static Set<String> parseValues(String definition) {
        Set<String> values = new LinkedHashSet<>();
        Matcher m = LITERAL.matcher(definition);
        while (m.find()) {
            values.add(m.group(1).replace("''", "'"));
        }
        return values;
    }

    private static final Pattern CAST = Pattern.compile("::[a-z ]+(\\[\\])?");

    /**
     * 값 목록 꼴인가: 이름이 {@code ck_표_열} 이고, 정의가 그 열 하나를 리터럴 목록과 견주는 것뿐이다.
     * 리터럴·형 변환·열 이름·{@code ANY}·{@code ARRAY}·{@code =}·괄호를 모두 걷어 냈을 때 아무것도 남지 않아야 한다 —
     * 다른 열이나 {@code AND}·{@code OR}·{@code <>}·함수가 섞인 조건 제약({@code ck_orders_limit_price} 등)은 목록이 아니다.
     */
    public static boolean isValueList(String table, String constraint, Set<String> columns, String definition) {
        if (definition.contains(" ~ ") || !constraint.startsWith("ck_" + table + "_")) {
            return false;
        }
        String column = constraint.substring(("ck_" + table + "_").length());
        if (!columns.contains(column) || parseValues(definition).isEmpty()) {
            return false;
        }
        String rest = LITERAL.matcher(definition).replaceAll("");
        rest = CAST.matcher(rest).replaceAll("");
        rest = rest.replace("CHECK", "").replace("ANY", "").replace("ARRAY", "").replaceAll("\\b" + column + "\\b", "");
        return rest.replaceAll("[\\s()\\[\\],=]", "").isEmpty();
    }

    /** public 스키마의 모든 값 목록 CHECK: {@code 표.열} → 값 집합. */
    public static Map<String, Set<String>> valueLists(JdbcTemplate jdbc) {
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        jdbc.query("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public'",
                rs -> {
                    columnsByTable.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                });
        Map<String, Set<String>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.relname, c.conname, pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE c.contype = 'c' AND n.nspname = 'public'
                ORDER BY t.relname, c.conname
                """, rs -> {
            String table = rs.getString(1);
            String name = rs.getString(2);
            String def = rs.getString(3);
            if (isValueList(table, name, columnsByTable.getOrDefault(table, Set.of()), def)) {
                String column = name.substring(("ck_" + table + "_").length());
                result.put(table + "." + column, parseValues(def));
            }
        });
        return result;
    }
}
