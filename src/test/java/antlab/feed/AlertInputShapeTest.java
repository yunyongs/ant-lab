package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Look-ahead guard at the type level for the alert input, as {@link FeedInputShapeTest} is for the feed: a closed set of scalar facts,
 * nothing that can carry a block, a month, a date, a path or a price row, and no component named like a future value.
 */
class AlertInputShapeTest {

    private static final Set<Class<?>> SCALARS = Set.of(int.class, long.class, boolean.class, String.class, BigDecimal.class,
            Integer.class, Long.class, antlab.history.MoveDirection.class);
    private static final Set<Class<?>> RECORDS = Set.of(AlertInput.class, AssetFacts.class, PlayerFacts.class);
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
            } else if (RECORDS.contains(type)) {
                walk((Class<?>) type, seen, names);
            } else {
                assertThat(SCALARS).as(record.getSimpleName() + "." + c.getName() + " is a scalar").contains((Class<?>) type);
            }
        }
    }

    @Test
    void the_input_graph_holds_only_scalars_and_the_fact_records() {
        Set<Class<?>> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        walk(AlertInput.class, seen, names);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(RECORDS);
        assertThat(names).isNotEmpty();
    }

    @Test
    void no_component_is_named_like_a_future_value_or_a_calendar_value() {
        List<String> names = new ArrayList<>();
        walk(AlertInput.class, new HashSet<>(), names);
        for (String name : names) {
            assertThat(FUTURE_NAME.matcher(name).find()).as("component " + name).isFalse();
        }
    }

    @Test
    void the_generator_has_no_spring_types_and_its_only_data_parameter_is_the_input() {
        assertThat(AlertGenerator.class.getAnnotations()).noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
        for (Constructor<?> c : AlertGenerator.class.getDeclaredConstructors()) {
            assertThat(c.getParameterTypes()).containsExactly(FeedTemplateCatalog.class);
        }
        List<Method> publicMethods = new ArrayList<>();
        for (Method m : AlertGenerator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m);
            }
        }
        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods.get(0).getName()).isEqualTo("generate");
        assertThat(publicMethods.get(0).getParameterTypes()).containsExactly(AlertInput.class, int.class);
        assertThat(java.util.Arrays.stream(AlertGenerator.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("Repository") || n.startsWith("org.springframework") || n.startsWith("java.time")
                        || n.contains("Random"));
    }
}
