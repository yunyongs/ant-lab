package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.member.MemberKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The season's latest value and total return: null at turn 0, the current turn's stored total from turn 1, rounded once. */
class SeasonDtoValueTest {

    private static Season season(String initialCash, int turns) {
        Season s = new Season(1L, 12, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal(initialCash), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
        for (int i = 0; i < turns; i++) {
            s.advanceTurn();
        }
        return s;
    }

    @Test
    void aNewSeasonHasNoValueOrReturn() {
        SeasonDto dto = SeasonDto.forNewSeason(season("100000000", 0), new BigDecimal("100000000"), null);

        assertThat(dto.latestTotalValue()).isNull();
        assertThat(dto.totalReturn()).isNull();
    }

    @Test
    void fromTurnOneTheStoredTotalIsWholeWonAndTheReturnHasEightPlaces() {
        SeasonDto dto = SeasonDto.from(season("100000000", 3), new BigDecimal("1200.0000"), MemberKind.HUMAN, null,
                new BigDecimal("98765432.0000"));

        assertThat(dto.latestTotalValue()).hasToString("98765432");
        assertThat(dto.totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("-0.01234568");
    }

    @Test
    void theReturnIsRoundedOnceHalfUpAwayFromZero() {
        // (30000001 - 30000000) / 30000000 = 0.0000000333... -> 0.00000003; (29999999 - 30000000) / ... -> -0.00000003
        assertThat(SeasonDto.from(season("30000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("30000001")).totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("0.00000003");
        assertThat(SeasonDto.from(season("30000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("29999999")).totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("-0.00000003");
        // 0.000000005 exactly: half up away from zero
        assertThat(SeasonDto.from(season("200000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("200000001")).totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("0.00000001");
        assertThat(SeasonDto.from(season("200000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("199999999")).totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("-0.00000001");
    }

    @Test
    void anUnchangedValueIsAZeroReturn() {
        SeasonDto dto = SeasonDto.from(season("100000000", 12), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("100000000.0000"));

        assertThat(dto.totalReturn()).extracting(BigDecimal::toPlainString).isEqualTo("0.00000000");
    }

    @Test
    void aMissingTurnValueFromTurnOneIsCorruptData() {
        assertThatThrownBy(() -> SeasonDto.from(season("100000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aValueAtTurnZeroIsRejected() {
        assertThatThrownBy(() -> SeasonDto.from(season("100000000", 0), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("100000000"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFractionalStoredTotalIsNotRoundedAway() {
        assertThatThrownBy(() -> SeasonDto.from(season("100000000", 1), BigDecimal.ZERO, MemberKind.HUMAN, null,
                new BigDecimal("100000000.5000"))).isInstanceOf(IllegalArgumentException.class);
    }
}
