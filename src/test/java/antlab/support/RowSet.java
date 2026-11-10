package antlab.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 타입을 보존한 조회 결과. 값은 메모리에만 두고 {@code toString} 으로 내보내지 않는다.
 * 셀 타입: String, Long, BigDecimal, LocalDate, Boolean, JsonNode, null.
 */
public final class RowSet {

    public enum ColumnType { TEXT, LONG, DECIMAL, DATE, BOOLEAN, JSON }

    public record RowColumn(String name, ColumnType type) {
    }

    /** 열 이름과 타입을 명시한 질의. {@code SELECT *} 에 기대지 않는다. SQL 의 {@code :sid} 는 시즌 번호(숫자)로 바뀐다. */
    public record RowQuery(String name, String sql, List<RowColumn> columns) {
    }

    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final List<RowColumn> columns;
    private final List<List<Object>> rows;

    public RowSet(List<RowColumn> columns, List<List<Object>> rows) {
        this.columns = List.copyOf(columns);
        List<List<Object>> copy = new ArrayList<>();
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("행의 칸 수가 열 수와 다르다");
            }
            copy.add(new ArrayList<>(row));
        }
        this.rows = copy;
    }

    public List<RowColumn> columns() {
        return columns;
    }

    public List<List<Object>> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    public int indexOf(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException("없는 열: " + column);
    }

    public Object get(int row, String column) {
        return rows.get(row).get(indexOf(column));
    }

    /** 비교용 사본. 메모리 안의 값만 바꿀 수 있고 DB 행은 건드리지 않는다. */
    public RowSet copy() {
        return new RowSet(columns, rows);
    }

    public void set(int row, String column, Object value) {
        rows.get(row).set(indexOf(column), value);
    }

    public void removeRow(int row) {
        rows.remove(row);
    }

    public void addRow(List<Object> row) {
        if (row.size() != columns.size()) {
            throw new IllegalArgumentException("행의 칸 수가 열 수와 다르다");
        }
        rows.add(new ArrayList<>(row));
    }

    /**
     * JSON 열 안의 알려진 ID 필드(이름으로 지정)를 논리 키 문자열로 바꾼 사본을 돌려준다.
     * 서로 다른 DB 의 대리 키를 같은 논리 대상으로 맞출 때 쓴다. 지정하지 않은 필드는 그대로 둔다.
     */
    public RowSet withJsonIdsMapped(String jsonColumn, java.util.Set<String> idFields, Function<Long, String> idToKey) {
        RowSet result = copy();
        int idx = indexOf(jsonColumn);
        for (List<Object> row : result.rows) {
            if (row.get(idx) instanceof JsonNode node) {
                row.set(idx, mapIds(node, idFields, idToKey));
            }
        }
        return result;
    }

    private static JsonNode mapIds(JsonNode node, java.util.Set<String> idFields, Function<Long, String> idToKey) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            for (var entry : node.properties()) {
                JsonNode value = entry.getValue();
                if (idFields.contains(entry.getKey()) && value.isIntegralNumber()) {
                    out.put(entry.getKey(), idToKey.apply(value.longValue()));
                } else {
                    out.set(entry.getKey(), mapIds(value, idFields, idToKey));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                out.add(mapIds(node.get(i), idFields, idToKey));
            }
            return out;
        }
        return node;
    }

    static Object parseCell(ColumnType type, String text) {
        if (text == null) {
            return null;
        }
        return switch (type) {
            case TEXT -> text;
            case LONG -> Long.valueOf(text);
            case DECIMAL -> new BigDecimal(text);
            case DATE -> LocalDate.parse(text);
            case BOOLEAN -> switch (text) {
                case "t", "true" -> Boolean.TRUE;
                case "f", "false" -> Boolean.FALSE;
                default -> throw new IllegalArgumentException("불리언이 아닌 값");
            };
            case JSON -> JSON.readTree(text);
        };
    }

    @Override
    public String toString() {
        return "RowSet[columns=" + columns.stream().map(RowColumn::name).toList() + ", rows=" + rows.size() + "]";
    }
}
