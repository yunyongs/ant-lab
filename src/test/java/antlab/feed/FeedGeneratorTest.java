package antlab.feed;

import static antlab.feed.FeedFacts.asset;
import static antlab.feed.FeedFacts.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeedGeneratorTest {

    private static final int TURN = 5;

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();
    private final FeedGenerator generator = new FeedGenerator(catalog);

    private static FeedInput input(long seed, List<AssetFacts> assets, List<EventFacts> events) {
        return new FeedInput(seed, TURN, assets, events);
    }

    private static List<EventFacts> publicEvents(int count) {
        List<EventFacts> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(event(TURN, 100 + i, "evt" + String.format("%03d", i), "TRADE", "0.02000000"));
        }
        return events;
    }

    private void assertRenderFailure(FeedInput input) {
        assertThatThrownBy(() -> generator.generate(input)).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_RENDER);
    }

    @Test
    void no_candidate_is_a_valid_zero_row_result() {
        assertThat(generator.generate(input(1, List.of(asset(1, "A1", "가나").build(), asset(2, "A2", "다라").build()), List.of())))
                .isEmpty();
        assertThat(generator.generate(input(1, List.of(), List.of()))).isEmpty();
    }

    @Test
    void events_only_gives_exactly_the_events() {
        List<GeneratedFeedRow> rows = generator.generate(input(1, List.of(asset(1, "A1", "가나").build()), publicEvents(2)));
        assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r.kind()).isEqualTo(FeedKind.EVENT));
        assertThat(rows).extracting(GeneratedFeedRow::seq).containsExactly(1, 2);
        assertThat(rows).extracting(GeneratedFeedRow::monthEventId).containsExactlyInAnyOrder(100L, 101L);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.assetId()).isNull();
            assertThat(r.assetGroupId()).isNull();
            assertThat(r.direction()).isNull();
            assertThat(r.repeatCount()).isZero();
            assertThat(r.templateVarsJson()).isEqualTo("{\"reaction\": 0.02000000}");
            assertThat(r.body()).contains("+2%");
        });
    }

    @Test
    void sixteen_events_fail_and_fifteen_pass() {
        assertRenderFailure(input(1, List.of(), publicEvents(16)));
        assertThat(generator.generate(input(1, List.of(), publicEvents(15)))).hasSize(15);
    }

    @Test
    void a_hidden_event_uses_only_hidden_templates_and_a_public_one_its_category_template() {
        EventFacts hidden = event(TURN, 7, "hid001", null, "-0.01000000");
        EventFacts trade = event(TURN, 8, "trd001", "TRADE", "0.01000000");
        List<GeneratedFeedRow> rows = generator.generate(input(3, List.of(), List.of(hidden, trade)));
        GeneratedFeedRow hiddenRow = rows.stream().filter(r -> r.monthEventId() == 7L).findFirst().orElseThrow();
        GeneratedFeedRow tradeRow = rows.stream().filter(r -> r.monthEventId() == 8L).findFirst().orElseThrow();
        assertThat(hiddenRow.categoryHidden()).isTrue();
        assertThat(catalog.active(hiddenRow.templateId()).orElseThrow().categoryHidden()).isTrue();
        assertThat(tradeRow.categoryHidden()).isFalse();
        assertThat(tradeRow.templateId()).isEqualTo("event.trade.v1");
        assertThat(hiddenRow.body()).contains("-1%");
    }

    @Test
    void an_event_without_a_template_is_a_render_failure() {
        assertRenderFailure(input(1, List.of(), List.of(event(TURN, 1, "odd001", "NO_SUCH_CATEGORY", "0.01000000"))));
    }

    @Test
    void a_tiny_asset_move_is_dropped_but_a_tiny_group_move_is_told_with_an_event_shaped_sentence() {
        AssetFacts tiny = asset(1, "A1", "가나").ret("0.00400000").build();
        List<GeneratedFeedRow> rows = generator.generate(input(2, List.of(tiny), List.of()));
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(FeedKind.RISE);
            assertThat(r.assetId()).isNull();
            assertThat(r.assetGroupId()).isEqualTo(1L);
            assertThat(r.body()).contains("0%").doesNotContain("기술");
            assertThat(catalog.shape(catalog.active(r.templateId()).orElseThrow().text())).isIn(eventShapes());
        });
    }

    private Set<String> eventShapes() {
        return Set.copyOf(catalog.templates().stream().filter(t -> t.active() && t.kind() == FeedKind.EVENT)
                .map(t -> catalog.shape(t.text())).toList());
    }

    @Test
    void a_zero_return_makes_neither_a_rise_nor_a_fall() {
        AssetFacts flat = asset(1, "A1", "가나").build();
        AssetFacts up = asset(2, "A2", "다라").ret("0.10000000").build();
        List<GeneratedFeedRow> rows = generator.generate(input(4, List.of(flat, up), List.of()));
        assertThat(rows).isNotEmpty().noneMatch(r -> r.assetId() != null && r.assetId() == 1L);
        assertThat(rows).allSatisfy(r -> assertThat(r.direction()).isEqualTo(MoveDirection.UP));
    }

    @Test
    void an_echo_needs_the_same_story_direction_the_same_sign_and_no_fall_in_attention_and_counts_up() {
        AssetFacts echo = asset(1, "A1", "가나").ret("0.05000000").prev("0.02000000", MoveDirection.UP, 3).build();
        assertThat(generator.generate(input(6, List.of(echo), List.of()))).filteredOn(r -> r.kind() == FeedKind.ECHO)
                .isNotEmpty().allSatisfy(r -> {
                    assertThat(r.repeatCount()).isEqualTo(3);
                    assertThat(r.direction()).isEqualTo(MoveDirection.UP);
                    assertThat(r.assetId()).isEqualTo(1L);
                });
        List<AssetFacts> noEcho = List.of(
                asset(1, "A1", "가나").ret("0.05000000").prev("0.02000000", null, 1).build(),
                asset(2, "A2", "다라").ret("0.05000000").prev("0.02000000", MoveDirection.DOWN, 1).build(),
                asset(3, "A3", "마바").ret("0.05000000").prev("-0.02000000", MoveDirection.UP, 1).build(),
                asset(4, "A4", "사아").ret("0.05000000").prev("0.00000000", MoveDirection.UP, 1).build(),
                asset(5, "A5", "자차").ret("0.05000000").prev("0.02000000", MoveDirection.UP, 1)
                        .attention("1.00000000", 1, 1, "2.00000000", 1).build(),
                asset(6, "A6", "카타").ret("0.05000000").build());
        assertThat(generator.generate(input(6, noEcho, List.of()))).noneMatch(r -> r.kind() == FeedKind.ECHO);
    }

    private List<GeneratedFeedRow> fadeRows(Integer previousRank, String focus, String now, String before) {
        AssetFacts fade = asset(1, "A1", "가나").attention(now, 3, 4, before, previousRank).focus(focus).build();
        return generator.generate(input(9, List.of(fade), List.of()));
    }

    @Test
    void a_fade_needs_a_fall_in_attention_from_the_top_five_and_a_different_focus_name() {
        assertThat(fadeRows(5, "다라", "1.00000000", "2.00000000")).isNotEmpty()
                .allSatisfy(r -> assertThat(r.kind()).isEqualTo(FeedKind.FADE));
        assertThat(fadeRows(6, "다라", "1.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(null, "다라", "1.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(5, "가나", "1.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(5, null, "1.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(5, "다라", "2.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(5, "다라", "3.00000000", "2.00000000")).isEmpty();
        assertThat(fadeRows(5, "다라", "1.00000000", null)).isEmpty();
        assertThat(fadeRows(5, "다라", null, "2.00000000")).isEmpty();
    }

    private List<GeneratedFeedRow> spreadRows(String now, String before) {
        AssetFacts a = asset(1, "A1", "가나").ret("0.06000000").attention(now, 1, 1, before, 1).build();
        return generator.generate(input(2, List.of(a), List.of()));
    }

    @Test
    void a_spread_needs_the_group_attention_to_have_risen_and_both_values_to_exist() {
        assertThat(spreadRows("2.00000000", "1.00000000")).anyMatch(r -> r.kind() == FeedKind.SPREAD);
        assertThat(spreadRows("1.00000000", "1.00000000")).noneMatch(r -> r.kind() == FeedKind.SPREAD);
        assertThat(spreadRows("1.00000000", "2.00000000")).noneMatch(r -> r.kind() == FeedKind.SPREAD);
        assertThat(spreadRows(null, "1.00000000")).noneMatch(r -> r.kind() == FeedKind.SPREAD);
        assertThat(spreadRows("2.00000000", null)).noneMatch(r -> r.kind() == FeedKind.SPREAD);
    }

    @Test
    void the_group_return_is_the_mean_rounded_once_and_a_zero_mean_group_is_not_told() {
        AssetFacts up = asset(1, "A1", "가나").ret("0.10000000").build();
        AssetFacts down = asset(2, "A2", "다라").ret("-0.10000000").build();
        List<GeneratedFeedRow> rows = generator.generate(input(3, List.of(up, down), List.of()));
        assertThat(rows).isNotEmpty().noneMatch(r -> r.assetGroupId() != null);
    }

    @Test
    void the_same_input_gives_the_same_rows_and_another_seed_another_order() {
        List<AssetFacts> assets = List.of(asset(1, "A1", "가나").ret("0.05000000").build(),
                asset(2, "A2", "다라").group(2, "G2", "금융").ret("-0.07000000").build(),
                asset(3, "A3", "마바").group(2, "G2", "금융").ret("0.03000000").build());
        List<EventFacts> events = List.of(event(TURN, 9, "trd001", "TRADE", "0.01000000"));
        FeedInput base = input(11, assets, events);
        assertThat(generator.generate(base)).isEqualTo(generator.generate(input(11, assets, events)));
        assertThat(generator.generate(input(12, assets, events))).isNotEqualTo(generator.generate(base));
        Set<Integer> eventPositions = new java.util.HashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            List<GeneratedFeedRow> rows = generator.generate(input(seed, assets, events));
            assertThat(rows).extracting(GeneratedFeedRow::seq).isEqualTo(java.util.stream.IntStream.rangeClosed(1, rows.size()).boxed().toList());
            eventPositions.add(rows.stream().filter(r -> r.kind() == FeedKind.EVENT).findFirst().orElseThrow().seq());
        }
        assertThat(eventPositions).as("the event card does not always come first").hasSizeGreaterThan(1).doesNotContain(0);
    }

    @Test
    void the_row_count_stays_within_five_to_fifteen_when_candidates_exist() {
        List<AssetFacts> assets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            assets.add(asset(i + 1, "T" + i, "종목" + (char) ('가' + i)).group(1 + i % 3, "G" + (i % 3), "그룹" + (i % 3))
                    .ret(i % 2 == 0 ? "0.04000000" : "-0.04000000").build());
        }
        for (long seed = 1; seed <= 30; seed++) {
            assertThat(generator.generate(input(seed, assets, List.of()))).hasSizeBetween(5, 15);
        }
    }

    @Test
    void the_grade_uses_integer_ceilings_of_a_tenth_and_a_half() {
        assertThat(FeedRules.grade(null, 10)).isNull();
        assertThat(FeedRules.grade(1, 0)).isNull();
        assertThat(FeedRules.grade(1, 10)).isEqualTo(3);
        assertThat(FeedRules.grade(2, 10)).isEqualTo(2);
        assertThat(FeedRules.grade(5, 10)).isEqualTo(2);
        assertThat(FeedRules.grade(6, 10)).isEqualTo(1);
        assertThat(FeedRules.grade(2, 11)).isEqualTo(3);
        assertThat(FeedRules.grade(3, 11)).isEqualTo(2);
        assertThat(FeedRules.grade(6, 11)).isEqualTo(2);
        assertThat(FeedRules.grade(7, 11)).isEqualTo(1);
        assertThat(FeedRules.grade(1, 1)).isEqualTo(3);
        assertThat(FeedRules.grade(1, 2)).isEqualTo(3);
        assertThat(FeedRules.grade(2, 2)).isEqualTo(1);
    }

    @Test
    void the_generated_variables_are_exactly_the_slots_of_the_chosen_template() {
        AssetFacts a = asset(1, "A1", "가나").ret("0.05000000").prev("0.02000000", MoveDirection.UP, 2).streak(3)
                .attention("1.00000000", 1, 2, "0.50000000", 2).build();
        for (long seed = 1; seed <= 20; seed++) {
            for (GeneratedFeedRow row : generator.generate(input(seed, List.of(a), List.of()))) {
                FeedTemplate t = catalog.active(row.templateId()).orElseThrow();
                for (String slot : t.placeholders()) {
                    assertThat(row.templateVarsJson()).contains("\"" + slot + "\": ");
                }
                assertThat(row.templateVarsJson().chars().filter(c -> c == ':').count()).isEqualTo(t.placeholders().size());
                assertThat(row.body()).doesNotContain("{").doesNotContain("}");
            }
        }
    }
}
