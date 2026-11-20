package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The state methods behind PAUSED and VOID (AG-02 A5), without a database. Refusals come first. */
class SeasonPauseVoidStateTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static Season season() {
        return new Season(1L, 12, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
    }

    @Test
    void onlyASeasonInProgressCanPause() {
        Season abandoning = season();
        abandoning.abandon();
        assertThatThrownBy(() -> abandoning.pause(T0)).isInstanceOf(IllegalStateException.class);
        Season paused = season();
        paused.pause(T0);
        assertThatThrownBy(() -> paused.pause(T0)).isInstanceOf(IllegalStateException.class);
        Season voided = season();
        voided.voidSeason(VoidReason.MODEL_GONE, "gone");
        assertThatThrownBy(() -> voided.pause(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPausedSeasonCannotTradeAdvanceHaltOrChangeFormation() {
        Season s = season();
        s.pause(T0);
        assertThatThrownBy(s::advanceTurn).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(s::halt).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.changeFormation(new Formation(10, 30, 30, 30)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(s.resume()).as("the halt resume does not touch a pause").isFalse();
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.PAUSED);
    }

    @Test
    void onlyAPausedSeasonContinues() {
        assertThatThrownBy(() -> season().continueAfterPause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pauseKeepsTheTurnAndResumeReturnsToTheSameTurn() {
        Season s = season();
        s.advanceTurn();
        s.advanceTurn();
        s.pause(T0);
        assertThat(s.getStatus()).isEqualTo(SeasonStatus.PAUSED);
        assertThat(s.getPausedAt()).isEqualTo(T0);
        assertThat(s.getCurrentTurnNo()).isEqualTo(2);

        s.continueAfterPause();

        assertThat(s.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);
        assertThat(s.getPausedAt()).isNull();
        assertThat(s.getCurrentTurnNo()).isEqualTo(2);
    }

    @Test
    void theSevenDayWindowEndsExactlyAtSevenDays() {
        Season s = season();
        s.pause(T0);
        Instant justBefore = T0.plus(Duration.ofDays(7)).minusNanos(1);
        assertThat(s.pauseExpired(T0)).isFalse();
        assertThat(s.pauseExpired(T0.plus(Duration.ofHours(6 * 24 + 23)))).as("6 days 23 hours").isFalse();
        assertThat(s.pauseExpired(justBefore)).isFalse();
        assertThat(s.pauseExpired(T0.plus(Duration.ofDays(7)))).as("exactly 7 days").isTrue();
        assertThat(s.pauseExpired(T0.plus(Duration.ofDays(8)))).isTrue();
    }

    @Test
    void aSeasonThatIsNotPausedNeverExpires() {
        assertThat(season().pauseExpired(T0.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void abandoningAPausedSeasonMarksTheCurrentTurnAndClearsThePause() {
        Season s = season();
        s.advanceTurn();
        s.pause(T0);

        s.abandon();

        assertThat(s.getStatus()).isEqualTo(SeasonStatus.ABANDONING);
        assertThat(s.getAbandonedTurnNo()).isEqualTo(1);
        assertThat(s.getPausedAt()).isNull();
    }

    @Test
    void aFinishedOrVoidSeasonCannotBeVoided() {
        Season finished = season();
        for (int i = 0; i < 12; i++) {
            finished.advanceTurn();
        }
        assertThat(finished.getStatus()).isEqualTo(SeasonStatus.FINISHED);
        assertThatThrownBy(() -> finished.voidSeason(VoidReason.ENGINE_DEFECT, "x"))
                .isInstanceOf(IllegalStateException.class);
        Season voided = season();
        voided.voidSeason(VoidReason.MODEL_GONE, "gone");
        assertThatThrownBy(() -> voided.voidSeason(VoidReason.MODEL_GONE, "gone"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidKeepsTheTurnRecordsTheDefectAndIsTerminal() {
        Season s = season();
        s.advanceTurn();
        s.pause(T0);

        s.voidSeason(VoidReason.RUNNER_DEFECT, "ledger replay differs");

        assertThat(s.getStatus()).isEqualTo(SeasonStatus.VOID);
        assertThat(s.getVoidReason()).isEqualTo(VoidReason.RUNNER_DEFECT);
        assertThat(s.getVoidNote()).isEqualTo("ledger replay differs");
        assertThat(s.getPausedAt()).isNull();
        assertThat(s.getCurrentTurnNo()).isEqualTo(1);
        assertThatThrownBy(s::advanceTurn).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(s::abandon).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(s::halt).isInstanceOf(IllegalStateException.class);
        assertThat(s.resume()).isFalse();
    }

    @Test
    void aRunningSeasonCanBeVoidedFromAnyUnfinishedStatus() {
        Season abandoning = season();
        abandoning.abandon();
        abandoning.voidSeason(VoidReason.ENGINE_DEFECT, "x");
        assertThat(abandoning.getStatus()).isEqualTo(SeasonStatus.VOID);
        Season halted = season();
        halted.halt();
        halted.voidSeason(VoidReason.ENGINE_DEFECT, "x");
        assertThat(halted.getStatus()).isEqualTo(SeasonStatus.VOID);
    }

    @Test
    void aReadShowsAnOverduePauseAsAnAbandonAtThePausedTurnButOnlyFromSevenDays() {
        Season s = season();
        s.advanceTurn();
        s.pause(T0);
        Instant live = T0.plus(Duration.ofHours(6 * 24 + 23));
        Instant over = T0.plus(Duration.ofDays(7));

        assertThat(s.effectiveStatus(live)).isEqualTo(SeasonStatus.PAUSED);
        assertThat(s.effectiveAbandonedTurnNo(live)).isNull();
        assertThat(s.effectiveStatus(over)).isEqualTo(SeasonStatus.ABANDONING);
        assertThat(s.effectiveAbandonedTurnNo(over)).isEqualTo(1);
        assertThat(s.getStatus()).as("a read never changes the stored state").isEqualTo(SeasonStatus.PAUSED);
        assertThat(season().effectiveStatus(over)).isEqualTo(SeasonStatus.IN_PROGRESS);
    }
}
