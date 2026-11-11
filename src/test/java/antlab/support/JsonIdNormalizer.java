package antlab.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 대사 로그 {@code detail} 의 대리 키를 두 DB 에서 비교할 수 있게 바꾼다. 같은 시나리오여도 SQL 기준 DB 와 API DB 는
 * 종목·라인업·주문·체결·원장 번호(IDENTITY)가 다르다.
 * <ul>
 *   <li>{@code asset_id} → 그 DB 의 {@code asset} 자연 키({@code 시장/티커}) 문자열 — 종목은 어느 DB 에서나 같은 것을 가리킨다.</li>
 *   <li>{@code lineup_id}·{@code other_lineup_id}·{@code order_id}·{@code execution_id}·{@code ledger_id}·
 *       {@code formation_change_id} → 값 대신 {@value #ID_MARK} 표지 (키가 있다는 사실만 비교한다).</li>
 *   <li>모르는 키는 그대로 두고 {@link Result#unknownKeys()} 로 알린다 — 조용히 통과시키지 않는다.</li>
 *   <li>{@code items} 의 각 원소에 정렬용 {@value #SORT_KEY} 를 더한다(종류·턴·종목·종류 목록). 배열 순서가 대리 키 순서라
 *       두 DB 에서 다를 수 있으므로 비교는 이 키로 정렬한 집합으로 한다.</li>
 * </ul>
 * 금액·가격은 {@code detail} 에 없다(계약). 값은 출력하지 않는다.
 */
public final class JsonIdNormalizer {

    public static final String ID_MARK = "<id>";
    public static final String SORT_KEY = "sort_key";

    private static final Set<String> SURROGATE_KEYS = Set.of("lineup_id", "other_lineup_id", "order_id", "execution_id",
            "ledger_id", "formation_change_id");
    private static final Set<String> PLAIN_KEYS = Set.of("kind", "kinds", "turn_no");
    private static final Set<String> TOP_KEYS = Set.of("items", "total", "truncated");

    /** @param unknownKeys 정규화 규칙에 없는 키 이름(중복 없음, 처음 만난 순서) */
    public record Result(JsonNode json, List<String> unknownKeys) {
    }

    private JsonIdNormalizer() {
    }

    /** @param assetKeys 그 DB 의 {@code asset.id} → {@code 시장/티커}. detail 이 null 이면 그대로 돌려준다. */
    public static Result normalize(JsonNode detail, Map<Long, String> assetKeys) {
        Set<String> unknown = new LinkedHashSet<>();
        if (detail == null || detail.isNull() || detail.isMissingNode()) {
            return new Result(detail, List.of());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (var entry : detail.properties()) {
            String key = entry.getKey();
            if (key.equals("items") && entry.getValue().isArray()) {
                out.set(key, normalizeItems((ArrayNode) entry.getValue(), assetKeys, unknown));
            } else {
                if (!TOP_KEYS.contains(key)) {
                    unknown.add(key);
                }
                out.set(key, entry.getValue());
            }
        }
        return new Result(out, new ArrayList<>(unknown));
    }

    private static ArrayNode normalizeItems(ArrayNode items, Map<Long, String> assetKeys, Set<String> unknown) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : items) {
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            for (var entry : item.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.equals("asset_id")) {
                    String natural = value.isNumber() ? assetKeys.get(value.longValue()) : null;
                    if (natural == null) {
                        unknown.add("asset_id(알 수 없는 종목)");
                        normalized.set(key, value);
                    } else {
                        normalized.put(key, natural);
                    }
                } else if (SURROGATE_KEYS.contains(key)) {
                    normalized.put(key, ID_MARK);
                } else {
                    if (!PLAIN_KEYS.contains(key)) {
                        unknown.add(key);
                    }
                    normalized.set(key, value);
                }
            }
            normalized.put(SORT_KEY, sortKey(normalized));
            out.add(normalized);
        }
        return out;
    }

    private static String sortKey(ObjectNode item) {
        return text(item, "kind") + "|" + text(item, "turn_no") + "|" + text(item, "asset_id") + "|" + text(item, "kinds");
    }

    private static String text(ObjectNode item, String key) {
        JsonNode v = item.get(key);
        return v == null ? "" : v.isString() ? v.stringValue() : v.toString();
    }
}
