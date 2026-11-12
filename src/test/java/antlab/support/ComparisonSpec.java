package antlab.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테이블별 비교 명세. 결과의 모든 열은 키·비교·제외 중 하나로 분류되어야 하며, 분류에 없는 열이 있으면 비교가 실패한다.
 * 제외 열에는 이유를 적는다. 키 열에는 가격·금액을 넣지 않는다(차이 보고에 키가 그대로 나온다).
 *
 * @param jsonSetArrays 계약상 집합인 JSON 배열: 배열을 가진 필드 이름 → 원소를 정렬할 키 필드
 */
public record ComparisonSpec(
        String name,
        List<String> keyColumns,
        List<String> valueColumns,
        Map<String, String> excludedColumns,
        Map<String, String> jsonSetArrays) {

    public ComparisonSpec {
        if (keyColumns.isEmpty()) {
            throw new IllegalArgumentException("행 키 열이 필요하다: " + name);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> classified = new ArrayList<>(keyColumns);
        classified.addAll(valueColumns);
        classified.addAll(excludedColumns.keySet());
        for (String column : classified) {
            if (!seen.add(column)) {
                throw new IllegalArgumentException("column classified more than once: " + column);
            }
        }
        excludedColumns.forEach((column, reason) -> {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("제외 열에는 이유가 필요하다: " + column);
            }
        });
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    Set<String> allColumns() {
        Set<String> all = new LinkedHashSet<>(keyColumns);
        all.addAll(valueColumns);
        all.addAll(excludedColumns.keySet());
        return all;
    }

    public static final class Builder {
        private final String name;
        private List<String> keys = List.of();
        private List<String> values = List.of();
        private final Map<String, String> excluded = new LinkedHashMap<>();
        private final Map<String, String> setArrays = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder keys(String... columns) {
            this.keys = List.of(columns);
            return this;
        }

        public Builder values(String... columns) {
            this.values = List.of(columns);
            return this;
        }

        public Builder exclude(String column, String reason) {
            if (excluded.containsKey(column)) {
                throw new IllegalArgumentException("column excluded more than once: " + column);
            }
            excluded.put(column, reason);
            return this;
        }

        public Builder jsonSetArray(String field, String elementKey) {
            setArrays.put(field, elementKey);
            return this;
        }

        public ComparisonSpec build() {
            return new ComparisonSpec(name, keys, values, Map.copyOf(excluded), Map.copyOf(setArrays));
        }
    }
}
