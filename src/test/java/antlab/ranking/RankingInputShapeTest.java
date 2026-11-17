package antlab.ranking;

import static antlab.ranking.RankingFacts.TURN;
import static antlab.ranking.RankingFacts.asset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.feed.AssetFacts;
import antlab.feed.FeedFailure;
import antlab.feed.FeedGenerationException;
import antlab.history.MoveDirection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Look-ahead guard at the type level: the board's input is a closed set of scalar facts. Nothing in its record graph can carry a
 * block, a month, a date, a path, a price row or a repository, and no component is named like a future value.
 */
class RankingInputShapeTest {

    private static final Set<Class<?>> SCALARS = Set.of(int.class, long.class, boolean.class, String.class, BigDecimal.class,
            Integer.class, Long.class, MoveDirection.class);
    private static final Set<Class<?>> RECORDS = Set.of(RankingInput.class, AssetFacts.class);
    /** The same deny patterns as the feed input shape test (template rule VAR_FUTURE), applied to component names. */
    private static final Pattern FUTURE_NAME = Pattern.compile("(?i)^(monthBlock|next|future|forward|fwd|lead|plus).*|.*t1$");

    private static void walk(Class<?> record, Set<Class<?>> seen, List<String> names) {
        if (!seen.add(record)) {
            return;
        }
        assertThat(record.isRecord()).as(record.getSimpleName()).isTrue();
        for (RecordComponent c : record.getRecordComponents()) {
            names.add(c.getName());
            Type type = c.getGenericType();
            if (type instanceof ParameterizedType p) {
                assertThat(p.getRawType()).as(record.getSimpleName() + "." + c.getName()).isEqualTo(List.class);
                Class<?> element = (Class<?>) p.getActualTypeArguments()[0];
                assertThat(RECORDS).as(c.getName() + " element").contains(element);
                walk(element, seen, names);
            } else {
                assertThat(SCALARS).as(record.getSimpleName() + "." + c.getName() + " is a scalar").contains((Class<?>) type);
            }
        }
    }

    private static void assertFeedInputFailure(Runnable construct) {
        assertThatThrownBy(construct::run).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_INPUT);
    }

    @Test
    void a_null_entry_in_the_fact_list_is_a_feed_input_failure() {
        List<AssetFacts> withNull = new ArrayList<>();
        withNull.add(null);
        assertFeedInputFailure(() -> new RankingInput(3, withNull));
    }

    @Test
    void the_input_graph_holds_only_scalars_and_the_asset_fact_record() {
        Set<Class<?>> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        walk(RankingInput.class, seen, names);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(RECORDS);
        assertThat(names).isNotEmpty();
    }

    @Test
    void no_component_is_named_like_a_future_value_or_a_calendar_value() {
        List<String> names = new ArrayList<>();
        walk(RankingInput.class, new HashSet<>(), names);
        for (String name : names) {
            assertThat(FUTURE_NAME.matcher(name).find()).as("component " + name).isFalse();
        }
        assertThat(FUTURE_NAME.matcher("nextBlockReturn").find()).isTrue();
        assertThat(FUTURE_NAME.matcher("monthBlockId").find()).isTrue();
    }

    @Test
    void a_fact_of_another_turn_cannot_be_put_in_the_input() {
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").turn(TURN + 1).build())));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").turn(TURN - 1).build())));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").build(), asset(2, "A2").turn(TURN + 1).build())));
        new RankingInput(TURN, List.of(asset(1, "A1").build()));
    }

    @Test
    void the_turn_the_list_and_the_asset_keys_are_checked() {
        assertFeedInputFailure(() -> new RankingInput(0, List.of()));
        assertFeedInputFailure(() -> new RankingInput(-1, List.of()));
        assertFeedInputFailure(() -> new RankingInput(1, null));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").build(), asset(2, "A1").build())));
        new RankingInput(TURN, List.of(asset(1, "A1").build(), asset(2, "A1").market("US").build()));
    }

    @Test
    void an_attention_figure_and_its_rank_must_agree() {
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").attention("1.00000000", null, 3).build())));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").attention(null, 1, 3).build())));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").attention("1.00000000", 4, 3).build())));
        assertFeedInputFailure(() -> new RankingInput(TURN, List.of(asset(1, "A1").attention("1.00000000", 0, 3).build())));
        new RankingInput(TURN, List.of(asset(1, "A1").attention("1.00000000", 3, 3).build()));
    }

    @Test
    void the_calculator_has_no_spring_types_and_its_only_data_parameter_is_the_input() {
        assertThat(RankingBoardCalculator.class.getAnnotations())
                .noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
        for (Constructor<?> c : RankingBoardCalculator.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(c.getModifiers())).isTrue();
        }
        List<Method> publicMethods = new ArrayList<>();
        for (Method m : RankingBoardCalculator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m);
            }
        }
        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods.get(0).getName()).isEqualTo("board");
        assertThat(publicMethods.get(0).getParameterTypes()).containsExactly(RankingInput.class);
        assertThat(Arrays.stream(RankingBoardCalculator.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("Repository") || n.startsWith("org.springframework") || n.startsWith("java.time"));
    }

    @Test
    void the_board_result_holds_no_component_named_like_a_date_or_a_block() {
        List<String> names = new ArrayList<>();
        for (Class<?> record : List.of(RankingBoard.class, RankingBoard.Asset.class, RankingBoard.Attention.class,
                RankingBoard.Streak.class, RankingBoard.Group.class)) {
            for (RecordComponent c : record.getRecordComponents()) {
                names.add(c.getName());
            }
        }
        assertThat(names).doesNotContain("attentionIndex", "index", "month", "block", "date", "path");
        assertThat(names).noneMatch(n -> FUTURE_NAME.matcher(n).find());
    }
}
