package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Halt and resume transitions on the entity (no DB). The failing cases come first. */
class SeasonHaltTest {

    private static Season season(int pathLength) {
        return new Season(1L, pathLength, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
    }

    private static Season abandoning() {
        Season s = season(3);
        ReflectionTestUtils.setField(s, "status", SeasonStatus.ABANDONING);
        ReflectionTestUtils.setField(s, "abandonedTurnNo", 0);
        return s;
    }

    @Test
    void finishedAndHaltedSeasonsCannotHalt() {
        Season finished = season(1);
        finished.advanceTurn();
        assertThat(finished.getStatus()).isEqualTo(SeasonStatus.FINISHED);
        assertThatThrownBy(finished::halt).isInstanceOf(IllegalStateException.class);
        assertThat(finished.getStatus()).isEqualTo(SeasonStatus.FINISHED);

        Season halted = season(3);
        halted.halt();
        assertThatThrownBy(halted::halt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inProgressAndAbandoningSeasonsHalt() {
        Season s = season(3);
        s.halt();
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.HALTED);
        Season a = abandoning();
        a.halt();
        assertThat(a.getStatus()).isEqualTo(SeasonStatus.HALTED);
    }

    @Test
    void haltedSeasonRefusesTurnAndFormationChanges() {
        Season s = season(3);
        s.halt();
        assertThatThrownBy(s::advanceTurn).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.changeFormation(new Formation(10, 30, 30, 30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumeRestoresInProgressWhenNoAbandonTurnAndAbandoningOtherwise() {
        Season plain = season(3);
        plain.halt();
        assertThat(plain.resume()).isTrue();
        assertThat(plain.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);

        Season a = abandoning();
        a.halt();
        assertThat(a.resume()).isTrue();
        assertThat(a.getStatus()).isEqualTo(SeasonStatus.ABANDONING);
    }

    @Test
    void resumeOnAnythingButHaltedChangesNothing() {
        Season running = season(3);
        assertThat(running.resume()).isFalse();
        assertThat(running.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);
        Season a = abandoning();
        assertThat(a.resume()).isFalse();
        assertThat(a.getStatus()).isEqualTo(SeasonStatus.ABANDONING);
        Season finished = season(1);
        finished.advanceTurn();
        assertThat(finished.resume()).isFalse();
        assertThat(finished.getStatus()).isEqualTo(SeasonStatus.FINISHED);
    }
}
