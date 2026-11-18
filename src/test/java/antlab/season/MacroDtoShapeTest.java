package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.exposure.ExposedFrom;
import antlab.history.AxisDirection;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The "no absolute level" shape test, without Spring: the macro part of the turn result can carry only the whitelisted
 * components, of numeric-and-direction types, from exactly the twelve exposed columns. A free text, a date, an id, a label or
 * an indicator value would have to change this test first.
 */
class MacroDtoShapeTest {

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static final Set<String> TWELVE = Set.of("month_block.rate_dir", "month_block.growth_dir",
            "month_block.fx_dir", "month_block.sentiment_dir", "month_block.rate_strength", "month_block.growth_strength",
            "month_block.fx_strength", "month_block.sentiment_strength", "month_block.prob_p1", "month_block.prob_p2",
            "month_block.prob_p3", "month_block.prob_p4");

    @Test
    void the_four_records_have_exactly_the_whitelisted_components() {
        assertThat(names(MacroDto.class)).containsExactly("axes", "probabilities");
        assertThat(names(MacroAxesDto.class)).containsExactly("rate", "growth", "fx", "sentiment");
        assertThat(names(MacroAxisDto.class)).containsExactly("direction", "strength");
        assertThat(names(RegimeProbabilitiesDto.class)).containsExactly("p1", "p2", "p3", "p4");
    }

    @Test
    void component_types_are_only_direction_decimal_and_the_nested_records() {
        Set<Class<?>> allowed = Set.of(AxisDirection.class, BigDecimal.class, MacroAxesDto.class, MacroAxisDto.class,
                RegimeProbabilitiesDto.class);
        for (Class<?> record : List.of(MacroDto.class, MacroAxesDto.class, MacroAxisDto.class, RegimeProbabilitiesDto.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(allowed).as(record.getSimpleName() + "." + component.getName()).contains(component.getType());
            }
        }
    }

    @Test
    void the_union_of_the_declared_sources_is_exactly_the_twelve_columns() {
        Set<String> sources = new TreeSet<>();
        for (Class<?> record : List.of(MacroAxisDto.class, RegimeProbabilitiesDto.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                ExposedFrom from = component.getAnnotation(ExposedFrom.class);
                assertThat(from).as(record.getSimpleName() + "." + component.getName()).isNotNull();
                sources.addAll(Arrays.asList(from.value()));
            }
        }
        assertThat(sources).isEqualTo(new TreeSet<>(TWELVE));
        for (Class<?> record : List.of(MacroDto.class, MacroAxesDto.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(component.getAnnotation(ExposedFrom.class)).as("nested holder " + component.getName()).isNull();
            }
        }
    }

    @Test
    void the_projection_row_has_exactly_twelve_components_and_none_names_a_month_label_or_id() {
        List<String> names = names(TurnMacroRow.class);
        assertThat(names).hasSize(12);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String banned : List.of("month", "label", "indicator", "event", "id", "date", "phase", "quality")) {
                assertThat(lower).as(name).doesNotContain(banned);
            }
        }
    }

    @Test
    void the_turn_result_has_exactly_one_component_that_comes_from_the_month_block_table() {
        List<String> fromMonthBlock = Arrays.stream(TurnResultDto.class.getRecordComponents())
                .filter(c -> c.getAnnotation(ExposedFrom.class) != null)
                .filter(c -> Arrays.stream(c.getAnnotation(ExposedFrom.class).value()).anyMatch(s -> s.startsWith("month_block.")))
                .map(RecordComponent::getName).toList();
        assertThat(fromMonthBlock).as("the macro holder is a nested record, not a tagged component").isEmpty();
        long macro = Arrays.stream(TurnResultDto.class.getRecordComponents()).filter(c -> c.getType() == MacroDto.class).count();
        assertThat(macro).isEqualTo(1);
        assertThat(TurnResultDto.class.getRecordComponents()[TurnResultDto.class.getRecordComponents().length - 1].getName())
                .isEqualTo("macro");
    }
}
