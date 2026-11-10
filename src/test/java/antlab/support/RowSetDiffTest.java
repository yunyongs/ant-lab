package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSetDiff.Kind;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RowSetDiffTest {

    private static final List<RowColumn> COLUMNS = List.of(
            new RowColumn("turn_no", ColumnType.LONG), new RowColumn("market", ColumnType.TEXT),
            new RowColumn("amount", ColumnType.DECIMAL), new RowColumn("memo", ColumnType.TEXT));

    private static final ComparisonSpec SPEC = ComparisonSpec.named("sample").keys("turn_no", "market")
            .values("amount", "memo").build();

    private static List<Object> row(long turn, String market, String amount, String memo) {
        return Arrays.asList(turn, market, amount == null ? null : new BigDecimal(amount), memo);
    }

    @SafeVarargs
    private static RowSet rows(List<Object>... rows) {
        return new RowSet(COLUMNS, List.of(rows));
    }

    @Test
    void 같은_행은_순서가_달라도_차이가_없다() {
        RowSet a = rows(row(1, "KR", "10.5000", "x"), row(2, "US", "3", "y"));
        RowSet b = rows(row(2, "US", "3", "y"), row(1, "KR", "10.5000", "x"));
        assertThat(RowSetDiff.compare(a, b, SPEC).isEmpty()).isTrue();
    }

    @Test
    void NUMERIC_자릿수_차이는_같은_값이다() {
        RowSet a = rows(row(1, "KR", "10.50", "x"), row(2, "KR", "0.0000", "x"));
        RowSet b = rows(row(1, "KR", "10.5000", "x"), row(2, "KR", "0", "x"));
        assertThat(RowSetDiff.compare(a, b, SPEC).isEmpty()).isTrue();
    }

    @Test
    void 아주_작은_값_차이도_잡는다() {
        RowSet a = rows(row(1, "KR", "10.5000", "x"));
        RowSet b = rows(row(1, "KR", "10.5001", "x"));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.count(Kind.CHANGED)).isEqualTo(1);
        assertThat(result.entries().get(0).changedColumns()).containsExactly("amount");
    }

    @Test
    void 한_행의_여러_열이_달라도_CHANGED_한_건이다() {
        RowSet a = rows(row(1, "KR", "1", "x"));
        RowSet b = rows(row(1, "KR", "2", "z"));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).changedColumns()).containsExactly("amount", "memo");
    }

    @Test
    void 누락과_추가를_구분한다() {
        RowSet a = rows(row(1, "KR", "1", "x"), row(2, "KR", "1", "x"));
        RowSet b = rows(row(1, "KR", "1", "x"), row(3, "KR", "1", "x"));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.count(Kind.REMOVED)).isEqualTo(1);
        assertThat(result.count(Kind.ADDED)).isEqualTo(1);
    }

    @Test
    void NULL은_빈_문자열_0과_다르다() {
        RowSet a = rows(row(1, "KR", null, null), row(2, "KR", "0", ""));
        RowSet b = rows(row(1, "KR", "0", ""), row(2, "KR", null, null));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.count(Kind.CHANGED)).isEqualTo(2);
        assertThat(RowSetDiff.compare(a, a.copy(), SPEC).isEmpty()).isTrue();
    }

    @Test
    void 중복_키는_집합으로_합치지_않고_보고한다() {
        RowSet a = rows(row(1, "KR", "1", "x"));
        RowSet b = rows(row(1, "KR", "1", "x"), row(1, "KR", "1", "x"));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.count(Kind.DUPLICATE_KEY)).isEqualTo(1);
        // 양쪽이 같은 수만큼 중복이어도 오류다
        assertThat(RowSetDiff.compare(b, b.copy(), SPEC).count(Kind.DUPLICATE_KEY)).isEqualTo(1);
    }

    @Test
    void 행_키가_NULL이면_조용히_넘기지_않고_실패한다() {
        RowSet a = rows(row(1, "KR", "1", "x"));
        RowSet b = new RowSet(COLUMNS, List.of(Arrays.asList(1L, null, new BigDecimal("1"), "x")));
        assertThatThrownBy(() -> RowSetDiff.compare(a, b, SPEC))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("market");
    }

    @Test
    void 분류하지_않은_열이_있으면_실패한다() {
        ComparisonSpec partial = ComparisonSpec.named("sample").keys("turn_no", "market").values("amount").build();
        RowSet a = rows(row(1, "KR", "1", "x"));
        assertThatThrownBy(() -> RowSetDiff.compare(a, a.copy(), partial))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("memo");
    }

    @Test
    void 제외_열은_이유가_있어야_하고_비교에서_빠진다() {
        assertThatThrownBy(() -> ComparisonSpec.named("s").keys("turn_no").exclude("memo", " ").build())
                .isInstanceOf(IllegalArgumentException.class);
        ComparisonSpec spec = ComparisonSpec.named("s").keys("turn_no", "market").values("amount")
                .exclude("memo", "화면 문구라 업무 값이 아니다").build();
        RowSet a = rows(row(1, "KR", "1", "x"));
        RowSet b = rows(row(1, "KR", "1", "다름"));
        assertThat(RowSetDiff.compare(a, b, spec).isEmpty()).isTrue();
    }

    @Test
    void 메모리_사본의_한_행_변경은_정확히_CHANGED_한_건이다() {
        RowSet original = rows(row(1, "KR", "100.0000", "x"), row(2, "KR", "200.0000", "x"), row(3, "KR", "300", "x"));
        RowSet mutated = original.copy();
        mutated.set(1, "amount", new BigDecimal("200.0001"));
        var result = RowSetDiff.compare(original, mutated, SPEC);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.count(Kind.CHANGED)).isEqualTo(1);
        // 원본은 바뀌지 않았다
        assertThat(original.get(1, "amount")).isEqualTo(new BigDecimal("200.0000"));
    }

    @Test
    void 보고와_예외_메시지에_값이_나오지_않는다() {
        RowSet a = rows(row(1, "KR", "123456.7800", "비밀문구"));
        RowSet b = rows(row(1, "KR", "654321.0000", "다른문구"));
        var result = RowSetDiff.compare(a, b, SPEC);
        assertThat(result.summary()).doesNotContain("123456").doesNotContain("654321").doesNotContain("문구");
        assertThatThrownBy(() -> RowSetDiff.assertNoDifference(a, b, SPEC))
                .hasMessageNotContaining("123456").hasMessageNotContaining("654321");
        assertThat(a.toString()).doesNotContain("123456");
    }

    // ---- JSON ----

    private static final List<RowColumn> JSON_COLUMNS = List.of(
            new RowColumn("code", ColumnType.TEXT), new RowColumn("detail", ColumnType.JSON));
    private static final ComparisonSpec JSON_SPEC = ComparisonSpec.named("json").keys("code").values("detail")
            .jsonSetArray("items", "k").build();

    private static RowSet json(String text) {
        return new RowSet(JSON_COLUMNS, List.of(List.of("C1", RowSet.parseCell(ColumnType.JSON, text))));
    }

    @Test
    void JSON_객체_키_순서와_숫자_자릿수는_무시한다() {
        assertThat(RowSetDiff.compare(json("{\"a\": 1.50, \"b\": [1, 2]}"), json("{\"b\": [1, 2], \"a\": 1.5}"), JSON_SPEC)
                .isEmpty()).isTrue();
    }

    @Test
    void JSON_배열은_기본으로_순서를_보존한다() {
        assertThat(RowSetDiff.compare(json("{\"b\": [1, 2]}"), json("{\"b\": [2, 1]}"), JSON_SPEC).count(Kind.CHANGED))
                .isEqualTo(1);
    }

    @Test
    void 집합으로_지정한_JSON_배열만_정렬해_비교한다() {
        String x = "{\"items\": [{\"k\": \"a\", \"v\": 1}, {\"k\": \"b\", \"v\": 2}]}";
        String y = "{\"items\": [{\"k\": \"b\", \"v\": 2}, {\"k\": \"a\", \"v\": 1}]}";
        String z = "{\"items\": [{\"k\": \"b\", \"v\": 3}, {\"k\": \"a\", \"v\": 1}]}";
        assertThat(RowSetDiff.compare(json(x), json(y), JSON_SPEC).isEmpty()).isTrue();
        assertThat(RowSetDiff.compare(json(x), json(z), JSON_SPEC).count(Kind.CHANGED)).isEqualTo(1);
    }

    @Test
    void JSON_null과_누락_필드는_다르다() {
        assertThat(RowSetDiff.compare(json("{\"a\": null}"), json("{}"), JSON_SPEC).count(Kind.CHANGED)).isEqualTo(1);
    }

    @Test
    void 서로_다른_DB의_ID를_논리_키로_바꿔_대응한다() {
        RowSet left = json("{\"order_id\": 11, \"n\": 1}").withJsonIdsMapped("detail", Set.of("order_id"), id -> "ord-" + (id - 10));
        RowSet right = json("{\"order_id\": 501, \"n\": 1}").withJsonIdsMapped("detail", Set.of("order_id"), id -> "ord-" + (id - 500));
        assertThat(RowSetDiff.compare(left, right, JSON_SPEC).isEmpty()).isTrue();
        // 지정하지 않은 필드는 그대로라 다르면 잡힌다
        RowSet other = json("{\"order_id\": 501, \"n\": 2}").withJsonIdsMapped("detail", Set.of("order_id"), id -> "ord-" + (id - 500));
        assertThat(RowSetDiff.compare(left, other, JSON_SPEC).count(Kind.CHANGED)).isEqualTo(1);
    }
}
