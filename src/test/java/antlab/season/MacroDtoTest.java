package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.history.AxisDirection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Row to DTO: stored values pass through unchanged, a null part stays null, a value that does not fit its scale is refused. */
class MacroDtoTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static TurnMacroRow full() {
        return new TurnMacroRow(AxisDirection.UP, d("0.50000000"), AxisDirection.DOWN, d("1.25000000"),
                AxisDirection.NEUTRAL, d("0.00000000"), AxisDirection.UP, d("0.75000000"),
                d("0.2500"), d("0.2500"), d("0.2500"), d("0.2500"));
    }

    @Test
    void a_full_row_keeps_every_value_and_scale() {
        MacroDto macro = MacroDto.from(full());
        assertThat(macro.axes().rate()).isEqualTo(new MacroAxisDto(AxisDirection.UP, d("0.50000000")));
        assertThat(macro.axes().growth().direction()).isEqualTo(AxisDirection.DOWN);
        assertThat(macro.axes().growth().strength().toPlainString()).isEqualTo("1.25000000");
        assertThat(macro.axes().fx().direction()).isEqualTo(AxisDirection.NEUTRAL);
        assertThat(macro.axes().fx().strength().toPlainString()).isEqualTo("0.00000000");
        assertThat(macro.axes().sentiment().strength().toPlainString()).isEqualTo("0.75000000");
        assertThat(macro.probabilities().p1().toPlainString()).isEqualTo("0.2500");
        assertThat(macro.probabilities().p4().toPlainString()).isEqualTo("0.2500");
    }

    @Test
    void each_probability_goes_to_its_own_component() {
        TurnMacroRow row = new TurnMacroRow(null, null, null, null, null, null, null, null,
                d("0.1000"), d("0.2000"), d("0.3000"), d("0.4000"));
        RegimeProbabilitiesDto p = MacroDto.from(row).probabilities();
        assertThat(new BigDecimal[] {p.p1(), p.p2(), p.p3(), p.p4()})
                .containsExactly(d("0.1000"), d("0.2000"), d("0.3000"), d("0.4000"));
    }

    @Test
    void each_axis_goes_to_its_own_component() {
        TurnMacroRow row = new TurnMacroRow(AxisDirection.UP, d("1.00000000"), AxisDirection.DOWN, d("2.00000000"),
                AxisDirection.NEUTRAL, d("3.00000000"), AxisDirection.UP, d("4.00000000"), null, null, null, null);
        MacroAxesDto axes = MacroDto.from(row).axes();
        assertThat(axes.rate().strength()).isEqualByComparingTo("1");
        assertThat(axes.growth().strength()).isEqualByComparingTo("2");
        assertThat(axes.fx().strength()).isEqualByComparingTo("3");
        assertThat(axes.sentiment().strength()).isEqualByComparingTo("4");
        assertThat(axes.fx().direction()).isEqualTo(AxisDirection.NEUTRAL);
    }

    @Test
    void one_null_axis_stays_null_while_the_others_remain() {
        TurnMacroRow row = new TurnMacroRow(AxisDirection.UP, d("0.50000000"), AxisDirection.DOWN, d("1.25000000"),
                null, null, AxisDirection.UP, d("0.75000000"), d("0.2500"), d("0.2500"), d("0.2500"), d("0.2500"));
        MacroDto macro = MacroDto.from(row);
        assertThat(macro.axes().fx()).isNull();
        assertThat(macro.axes().rate()).isNotNull();
        assertThat(macro.probabilities()).isNotNull();
    }

    @Test
    void null_probabilities_stay_null_with_axes_present() {
        TurnMacroRow row = new TurnMacroRow(AxisDirection.UP, d("0.50000000"), AxisDirection.DOWN, d("1.25000000"),
                AxisDirection.NEUTRAL, d("0.00000000"), AxisDirection.UP, d("0.75000000"), null, null, null, null);
        MacroDto macro = MacroDto.from(row);
        assertThat(macro.probabilities()).isNull();
        assertThat(macro.axes().sentiment()).isNotNull();
    }

    @Test
    void an_all_null_row_gives_four_null_axes_and_null_probabilities_never_neutral_or_zero() {
        MacroDto macro = MacroDto.from(new TurnMacroRow(null, null, null, null, null, null, null, null,
                null, null, null, null));
        assertThat(macro.axes()).isEqualTo(new MacroAxesDto(null, null, null, null));
        assertThat(macro.probabilities()).isNull();
    }

    @Test
    void a_value_with_a_shorter_scale_is_padded_without_changing_it() {
        TurnMacroRow row = new TurnMacroRow(AxisDirection.UP, d("0.5"), null, null, null, null, null, null,
                d("0.25"), d("0.25"), d("0.25"), d("0.25"));
        MacroDto macro = MacroDto.from(row);
        assertThat(macro.axes().rate().strength().toPlainString()).isEqualTo("0.50000000");
        assertThat(macro.probabilities().p2().toPlainString()).isEqualTo("0.2500");
    }

    @Test
    void a_value_that_does_not_fit_the_scale_exactly_is_refused_not_rounded() {
        TurnMacroRow longStrength = new TurnMacroRow(AxisDirection.UP, d("0.123456789"), null, null, null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> MacroDto.from(longStrength)).isInstanceOf(RuntimeException.class);
        TurnMacroRow longProbability = new TurnMacroRow(null, null, null, null, null, null, null, null,
                d("0.25001"), d("0.25"), d("0.25"), d("0.24999"));
        assertThatThrownBy(() -> MacroDto.from(longProbability)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_direction_without_strength_or_part_of_the_probabilities_is_a_corrupt_row() {
        TurnMacroRow half = new TurnMacroRow(AxisDirection.UP, null, null, null, null, null, null, null,
                null, null, null, null);
        assertThatThrownBy(() -> MacroDto.from(half)).isInstanceOf(IllegalStateException.class);
        TurnMacroRow partial = new TurnMacroRow(null, null, null, null, null, null, null, null,
                d("0.2500"), null, null, null);
        assertThatThrownBy(() -> MacroDto.from(partial)).isInstanceOf(IllegalStateException.class);
    }
}
