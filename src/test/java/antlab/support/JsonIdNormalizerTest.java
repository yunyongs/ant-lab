package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 대사 로그 detail 정규화 단위 시험 — 합성 JSON 만 쓴다(구조는 규칙 17·19 가 실제로 남기는 모양: 종류·턴·종목 번호). */
class JsonIdNormalizerTest {

    private static JsonNode parse(String json) {
        return JsonMapper.shared().readTree(json);
    }

    private static final Map<Long, String> SQL_DB = Map.of(11L, "KR/069500", 12L, "US/SPY");
    private static final Map<Long, String> API_DB = Map.of(5L, "KR/069500", 6L, "US/SPY");

    @Test
    void 종목_번호는_자연_키로_다른_대리_키는_표지로_바뀐다() {
        var result = JsonIdNormalizer.normalize(parse("""
                {"items": [{"kind": "OVERLAP", "lineup_id": 41, "other_lineup_id": 42},
                           {"kind": "NO_LINEUP", "asset_id": 11}], "total": 2, "truncated": false}"""), SQL_DB);
        assertThat(result.unknownKeys()).isEmpty();
        JsonNode items = result.json().get("items");
        assertThat(items.get(0).get("lineup_id").stringValue()).isEqualTo(JsonIdNormalizer.ID_MARK);
        assertThat(items.get(0).get("other_lineup_id").stringValue()).isEqualTo(JsonIdNormalizer.ID_MARK);
        assertThat(items.get(1).get("asset_id").stringValue()).isEqualTo("KR/069500");
        assertThat(result.json().get("total").intValue()).isEqualTo(2);
    }

    @Test
    void 번호만_다르고_순서도_다른_같은_내용은_정규화_뒤_같다() {
        String sql = """
                {"items": [{"kind": "A", "turn_no": 1, "asset_id": 11}, {"kind": "A", "turn_no": 1, "asset_id": 12}],
                 "total": 2, "truncated": false}""";
        String api = """
                {"items": [{"kind": "A", "turn_no": 1, "asset_id": 6}, {"kind": "A", "turn_no": 1, "asset_id": 5}],
                 "total": 2, "truncated": false}""";
        RowSet left = rows(JsonIdNormalizer.normalize(parse(sql), SQL_DB).json());
        RowSet right = rows(JsonIdNormalizer.normalize(parse(api), API_DB).json());
        // 배열 순서가 달라도 집합으로 비교하면 같다
        RowSetDiff.assertNoDifference(left, right, spec());
    }

    @Test
    void 내용이_다르면_정규화_뒤에도_다르다() {
        var one = JsonIdNormalizer.normalize(parse("{\"items\": [{\"kind\": \"A\", \"asset_id\": 11}], \"total\": 1}"), SQL_DB);
        var other = JsonIdNormalizer.normalize(parse("{\"items\": [{\"kind\": \"A\", \"asset_id\": 12}], \"total\": 1}"), SQL_DB);
        assertThat(one.json()).isNotEqualTo(other.json());
    }

    @Test
    void 모르는_키는_그대로_두고_보고하며_모르는_종목도_보고한다() {
        var result = JsonIdNormalizer.normalize(parse("""
                {"items": [{"kind": "X", "mystery_id": 7, "asset_id": 99}], "total": 1, "extra": true}"""), SQL_DB);
        assertThat(result.unknownKeys()).contains("mystery_id", "extra").anyMatch(k -> k.startsWith("asset_id"));
        assertThat(result.json().get("items").get(0).get("mystery_id").intValue()).isEqualTo(7);
    }

    @Test
    void 상세가_없으면_그대로다() {
        var result = JsonIdNormalizer.normalize(null, SQL_DB);
        assertThat(result.json()).isNull();
        assertThat(result.unknownKeys()).isEmpty();
    }

    private static ComparisonSpec spec() {
        return ComparisonSpec.named("t").keys("id").values("detail").jsonSetArray("items", JsonIdNormalizer.SORT_KEY).build();
    }

    private static RowSet rows(JsonNode detail) {
        return new RowSet(List.of(new RowColumn("id", ColumnType.LONG), new RowColumn("detail", ColumnType.JSON)),
                List.of(List.of(1L, detail)));
    }
}
