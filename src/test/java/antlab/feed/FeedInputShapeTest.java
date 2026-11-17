package antlab.feed;

import static antlab.feed.FeedFacts.asset;
import static antlab.feed.FeedFacts.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Look-ahead guard at the type level (PLAN section 2 anti-goal): the generator's input is a closed set of scalar facts. Nothing in its
 * record graph can carry a block, a month, a date, a path, a price row or a repository, and no component is named like a future value.
 */
class FeedInputShapeTest {

    private static final Set<Class<?>> SCALARS = Set.of(int.class, long.class, boolean.class, String.class, BigDecimal.class,
            Integer.class, Long.class, antlab.history.MoveDirection.class);
    private static final Set<Class<?>> RECORDS = Set.of(FeedInput.class, AssetFacts.class, EventFacts.class);
    /** The deny patterns of the template variable rule VAR_FUTURE, applied to component names. */
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

    @Test
    void the_input_graph_holds_only_scalars_and_the_three_fact_records() {
        Set<Class<?>> seen = new java.util.HashSet<>();
        List<String> names = new ArrayList<>();
        walk(FeedInput.class, seen, names);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(RECORDS);
        assertThat(names).isNotEmpty();
    }

    @Test
    void no_component_is_named_like_a_future_value_or_a_calendar_value() {
        List<String> names = new ArrayList<>();
        walk(FeedInput.class, new java.util.HashSet<>(), names);
        for (String name : names) {
            assertThat(FUTURE_NAME.matcher(name).find()).as("component " + name).isFalse();
        }
        assertThat(FUTURE_NAME.matcher("nextBlockReturn").find()).isTrue();
        assertThat(FUTURE_NAME.matcher("monthBlockId").find()).isTrue();
        assertThat(FUTURE_NAME.matcher("retT1").find()).isTrue();
        assertThat(FUTURE_NAME.matcher("prevRet").find()).isFalse();
        assertThat(FUTURE_NAME.matcher("monthEventId").find()).isFalse();
    }

    @Test
    void a_fact_of_another_turn_cannot_be_put_in_the_input() {
        assertThatThrownBy(() -> new FeedInput(1, 5, List.of(asset(1, "A1", "가나").turn(6).build()), List.of()))
                .isInstanceOf(FeedGenerationException.class).extracting(e -> ((FeedGenerationException) e).failure())
                .isEqualTo(FeedFailure.FEED_INPUT);
        assertThatThrownBy(() -> new FeedInput(1, 5, List.of(), List.of(event(4, 1, "e1", "TRADE", "0.01"))))
                .isInstanceOf(FeedGenerationException.class);
        assertThatThrownBy(() -> new FeedInput(1, 0, List.of(), List.of())).isInstanceOf(FeedGenerationException.class);
        assertThatThrownBy(() -> new FeedInput(1, 1, null, List.of())).isInstanceOf(FeedGenerationException.class);
        assertThatThrownBy(() -> new FeedInput(1, 5, List.of(asset(1, "A1", "가나").build(), asset(2, "A1", "다라").build()), List.of()))
                .isInstanceOf(FeedGenerationException.class);
    }

    @Test
    void a_return_must_have_scale_eight() {
        assertThatThrownBy(() -> asset(1, "A1", "가나").ret("0.100000").build()).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_INPUT);
        assertThatThrownBy(() -> asset(1, "A1", "가나").prev("0.1", null, 1).build()).isInstanceOf(FeedGenerationException.class);
        asset(1, "A1", "가나").ret("0.10000000").prev("-0.00000000", null, 1).build();
    }

    @Test
    void an_event_without_a_reaction_is_a_reaction_missing_failure() {
        assertThatThrownBy(() -> new EventFacts(5, 1, "e1", "TRADE", null, 2, false)).isInstanceOf(FeedGenerationException.class)
                .extracting(e -> ((FeedGenerationException) e).failure()).isEqualTo(FeedFailure.FEED_REACTION_MISSING);
    }

    @Test
    void a_hidden_event_cannot_carry_its_category_and_a_public_one_needs_it() {
        assertThatThrownBy(() -> new EventFacts(5, 1, "e1", "TRADE", new BigDecimal("0.01"), 2, true))
                .isInstanceOf(FeedGenerationException.class).extracting(e -> ((FeedGenerationException) e).failure())
                .isEqualTo(FeedFailure.FEED_INPUT);
        assertThatThrownBy(() -> new EventFacts(5, 1, "e1", null, new BigDecimal("0.01"), 2, false))
                .isInstanceOf(FeedGenerationException.class);
    }

    @Test
    void the_generator_has_no_spring_types_and_its_only_data_parameter_is_the_input() {
        assertThat(FeedGenerator.class.getAnnotations()).noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
        for (Constructor<?> c : FeedGenerator.class.getDeclaredConstructors()) {
            assertThat(c.getParameterTypes()).containsExactly(FeedTemplateCatalog.class);
        }
        List<Method> publicMethods = new ArrayList<>();
        for (Method m : FeedGenerator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m);
            }
        }
        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods.get(0).getName()).isEqualTo("generate");
        assertThat(publicMethods.get(0).getParameterTypes()).containsExactly(FeedInput.class);
        assertThat(java.util.Arrays.stream(FeedGenerator.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("Repository") || n.startsWith("org.springframework") || n.startsWith("java.time"));
    }
}
