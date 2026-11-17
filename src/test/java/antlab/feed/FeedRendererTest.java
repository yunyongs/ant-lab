package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeedRendererTest {

    private static final Set<FeedKind> ALL = Set.of(FeedKind.values());

    private static final Map<String, FeedVariable> VARIABLES = Map.of(
            "n", new FeedVariable("n", "name", "", 0, ALL, 6),
            "s", new FeedVariable("s", "pct_signed", "", 0, ALL, 8),
            "u", new FeedVariable("u", "pct_unsigned", "", 0, ALL, 8),
            "i", new FeedVariable("i", "int", "", 0, ALL, 3));

    private final FeedRenderer renderer = new FeedRenderer(VARIABLES);

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private String signed(String ratio) {
        return renderer.render("{s}", values("s", bd(ratio)));
    }

    @Test
    void a_signed_percent_rounds_half_away_from_zero_with_an_ascii_minus() {
        assertThat(signed("0.18234567")).isEqualTo("+18%");
        assertThat(signed("-0.004")).isEqualTo("0%");
        assertThat(signed("0.005")).isEqualTo("+1%");
        assertThat(signed("-0.005")).isEqualTo("-1%");
        assertThat(signed("0.00499999")).isEqualTo("0%");
        assertThat(signed("-0.18234567")).isEqualTo("-18%");
        assertThat(signed("-0.18234567")).doesNotContain("−");
        assertThat(signed("0.00000000")).isEqualTo("0%");
    }

    @Test
    void an_unsigned_percent_has_no_plus_and_an_int_is_plain_decimal() {
        assertThat(renderer.render("{u}", values("u", bd("0.35")))).isEqualTo("35%");
        assertThat(renderer.render("{u}", values("u", bd("0.004")))).isEqualTo("0%");
        assertThat(renderer.render("{i}", values("i", bd("3")))).isEqualTo("3");
        assertThat(renderer.render("{i}", values("i", bd("3.000")))).isEqualTo("3");
    }

    @Test
    void slots_are_replaced_once_left_to_right_and_a_brace_inside_a_value_is_left_alone() {
        assertThat(renderer.render("{n}-{n}!", values("n", "ab"))).isEqualTo("ab-ab!");
        assertThat(renderer.render("[{n}] {i}", values("n", "{i}", "i", bd("7")))).isEqualTo("[{i}] 7");
    }

    @Test
    void a_missing_null_extra_or_mistyped_value_is_a_render_failure() {
        assertFails("{n} {i}", values("n", "x"));
        assertFails("{n}", values("n", null));
        assertFails("{n}", values("n", "x", "i", bd("1")));
        assertFails("{n}", values("n", bd("1")));
        assertFails("{s}", values("s", "0.1"));
        assertFails("{i}", values("i", bd("1.5")));
        assertFails("{x}", values("x", "a"));
        assertFails("{N}", values("N", "a"));
        assertFails("{n", values("n", "a"));
    }

    @Test
    void the_variable_and_body_length_limits_count_code_points() {
        assertThat(renderer.render("{n}", values("n", "가나다라마바"))).isEqualTo("가나다라마바");
        assertFails("{n}", values("n", "가나다라마바사"));
        assertFails("{i}", values("i", bd("1000")));
        assertThat(renderer.render("a".repeat(300), values())).hasSize(300);
        assertFails("a".repeat(301), values());
    }

    @Test
    void a_slot_free_sentence_renders_as_it_is() {
        assertThat(renderer.render("고정 문장", values())).isEqualTo("고정 문장");
    }

    private void assertFails(String text, Map<String, Object> values) {
        assertThatThrownBy(() -> renderer.render(text, values)).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_RENDER);
    }
}
