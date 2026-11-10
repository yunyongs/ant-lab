package antlab.support;

import antlab.support.RowSet.RowColumn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * 두 결과 집합을 정확히 비교한다.
 *
 * <ul>
 *   <li>NUMERIC 은 {@code BigDecimal.compareTo} 기준(자릿수 차이 허용, 허용 오차·반올림 없음).</li>
 *   <li>NULL 은 빈 문자열·0·누락과 구분한다.</li>
 *   <li>행 순서는 무시하고 행 키로 대응한다. 중복 키는 집합으로 합치지 않고 따로 보고한다.</li>
 *   <li>같은 키의 한 행은 여러 열이 달라도 CHANGED 한 건이다.</li>
 *   <li>보고에는 테이블·키·다른 열 이름·건수만 담는다. 값은 담지 않는다.</li>
 * </ul>
 */
public final class RowSetDiff {

    public enum Kind { ADDED, REMOVED, CHANGED, DUPLICATE_KEY }

    /** 차이 한 건. {@code detail} 은 CHANGED 의 다른 열 이름, DUPLICATE_KEY 의 (기대, 실제) 중복 행 수다. */
    public record Entry(Kind kind, String key, List<String> changedColumns, String detail) {
    }

    public record Result(String table, int expectedRows, int actualRows, List<Entry> entries) {
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public long count(Kind kind) {
            return entries.stream().filter(e -> e.kind() == kind).count();
        }

        /** 값 없이 구조만 보여 주는 요약 */
        public String summary() {
            StringBuilder sb = new StringBuilder(table).append(": 기대 ").append(expectedRows).append("행, 실제 ")
                    .append(actualRows).append("행, ADDED ").append(count(Kind.ADDED))
                    .append(", REMOVED ").append(count(Kind.REMOVED))
                    .append(", CHANGED ").append(count(Kind.CHANGED))
                    .append(", DUPLICATE_KEY ").append(count(Kind.DUPLICATE_KEY));
            int shown = 0;
            for (Entry e : entries) {
                if (shown++ == 10) {
                    sb.append("\n  … (").append(entries.size() - 10).append("건 더)");
                    break;
                }
                sb.append("\n  ").append(e.kind()).append(" [").append(e.key()).append(']');
                if (!e.changedColumns().isEmpty()) {
                    sb.append(" 열: ").append(String.join(", ", e.changedColumns()));
                }
                if (e.detail() != null) {
                    sb.append(' ').append(e.detail());
                }
            }
            return sb.toString();
        }
    }

    private static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "<NULL>";
        }
    };

    private RowSetDiff() {
    }

    public static Result compare(RowSet expected, RowSet actual, ComparisonSpec spec) {
        validateColumns("기대", expected, spec);
        validateColumns("실제", actual, spec);
        Indexed exp = index(expected, spec);
        Indexed act = index(actual, spec);

        List<Entry> entries = new ArrayList<>();
        Set<String> dupKeys = new LinkedHashSet<>(exp.duplicates.keySet());
        dupKeys.addAll(act.duplicates.keySet());
        for (String key : dupKeys) {
            entries.add(new Entry(Kind.DUPLICATE_KEY, key, List.of(),
                    "기대 " + (exp.duplicates.getOrDefault(key, 0) + (exp.rows.containsKey(key) ? 1 : 0))
                            + "행, 실제 " + (act.duplicates.getOrDefault(key, 0) + (act.rows.containsKey(key) ? 1 : 0)) + "행"));
        }
        for (String key : exp.rows.keySet()) {
            if (dupKeys.contains(key)) {
                continue;
            }
            Map<String, Object> other = act.rows.get(key);
            if (other == null) {
                entries.add(new Entry(Kind.REMOVED, key, List.of(), null));
                continue;
            }
            List<String> changed = new ArrayList<>();
            for (String column : spec.valueColumns()) {
                if (!exp.rows.get(key).get(column).equals(other.get(column))) {
                    changed.add(column);
                }
            }
            if (!changed.isEmpty()) {
                entries.add(new Entry(Kind.CHANGED, key, changed, null));
            }
        }
        for (String key : act.rows.keySet()) {
            if (!dupKeys.contains(key) && !exp.rows.containsKey(key)) {
                entries.add(new Entry(Kind.ADDED, key, List.of(), null));
            }
        }
        return new Result(spec.name(), expected.size(), actual.size(), List.copyOf(entries));
    }

    /** 차이가 있으면 요약(값 없음)으로 실패시킨다. */
    public static void assertNoDifference(RowSet expected, RowSet actual, ComparisonSpec spec) {
        Result result = compare(expected, actual, spec);
        if (!result.isEmpty()) {
            throw new AssertionError(result.summary());
        }
    }

    private record Indexed(Map<String, Map<String, Object>> rows, Map<String, Integer> duplicates) {
    }

    private static void validateColumns(String side, RowSet rowSet, ComparisonSpec spec) {
        Set<String> actualColumns = new LinkedHashSet<>();
        for (RowColumn c : rowSet.columns()) {
            if (!actualColumns.add(c.name())) {
                throw new IllegalStateException(spec.name() + " " + side + ": 열 이름이 중복된다: " + c.name());
            }
        }
        Set<String> declared = spec.allColumns();
        Set<String> unclassified = new LinkedHashSet<>(actualColumns);
        unclassified.removeAll(declared);
        Set<String> missing = new LinkedHashSet<>(declared);
        missing.removeAll(actualColumns);
        if (!unclassified.isEmpty() || !missing.isEmpty()) {
            throw new IllegalStateException(spec.name() + " " + side + ": 열 분류가 맞지 않는다. 분류 없음=" + unclassified
                    + ", 결과에 없음=" + missing);
        }
    }

    private static Indexed index(RowSet rowSet, ComparisonSpec spec) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Map<String, Integer> duplicates = new LinkedHashMap<>();
        int[] keyIdx = spec.keyColumns().stream().mapToInt(rowSet::indexOf).toArray();
        for (int r = 0; r < rowSet.size(); r++) {
            List<Object> row = rowSet.rows().get(r);
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < keyIdx.length; i++) {
                Object cell = row.get(keyIdx[i]);
                if (cell == null) {
                    // 대응하지 못한 FK 가 키로 빠져 나온 경우다. 조용히 버리거나 합치지 않는다.
                    throw new IllegalStateException(spec.name() + ": 행 키 열 " + spec.keyColumns().get(i)
                            + " 이(가) NULL 이다 (자연 키로 대응하지 못한 행, 행 번호 " + r + ")");
                }
                if (i > 0) {
                    key.append('/');
                }
                key.append(cell instanceof BigDecimal b ? plain(b) : String.valueOf(cell));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : spec.valueColumns()) {
                RowColumn meta = rowSet.columns().get(rowSet.indexOf(column));
                values.put(column, canonical(row.get(rowSet.indexOf(column)), meta, spec));
            }
            String k = key.toString();
            if (rows.containsKey(k)) {
                duplicates.merge(k, 1, Integer::sum);
            } else {
                rows.put(k, values);
            }
        }
        return new Indexed(rows, duplicates);
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static Object canonical(Object cell, RowColumn column, ComparisonSpec spec) {
        if (cell == null) {
            return NULL;
        }
        if (cell instanceof BigDecimal b) {
            return b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros();
        }
        if (cell instanceof JsonNode node) {
            return json(node, spec);
        }
        return cell;
    }

    /** JSON 을 객체 키 순서와 숫자 자릿수에 영향받지 않는 자바 구조로 바꾼다. 배열 순서는 기본으로 보존한다. */
    private static Object json(JsonNode node, ComparisonSpec spec) {
        if (node.isNull() || node.isMissingNode()) {
            return NULL;
        }
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            for (var entry : node.properties()) {
                JsonNode value = entry.getValue();
                String elementKey = spec.jsonSetArrays().get(entry.getKey());
                if (elementKey != null && value.isArray()) {
                    out.put(entry.getKey(), sortedSetArray(value, elementKey, spec));
                } else {
                    out.put(entry.getKey(), json(value, spec));
                }
            }
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                out.add(json(node.get(i), spec));
            }
            return out;
        }
        if (node.isNumber()) {
            BigDecimal b = node.decimalValue();
            return b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isString()) {
            return node.stringValue();
        }
        throw new IllegalStateException("지원하지 않는 JSON 값 종류");
    }

    private static List<Object> sortedSetArray(JsonNode array, String elementKey, ComparisonSpec spec) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            if (!element.isObject() || !element.has(elementKey)) {
                throw new IllegalStateException("집합 배열의 원소에 정렬 키 " + elementKey + " 가 없다");
            }
            items.add(json(element, spec));
        }
        items.sort(Comparator.comparing(o -> String.valueOf(((Map<?, ?>) o).get(elementKey))));
        return items;
    }
}
