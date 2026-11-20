package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Formation;
import antlab.season.Season;
import antlab.season.SeasonDto;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import antlab.season.VoidReason;
import antlab.support.ScriptedClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Pause, resume, void and the lazy 7-day rule of an agent season, with mocks and a scripted clock. */
class AgentSeasonLifecycleServiceTest {

    private static final long AGENT = 7L;
    private static final long SEASON = 70L;
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SeasonRepository seasons = mock(SeasonRepository.class);
    private final CashAccountRepository accounts = mock(CashAccountRepository.class);
    private final ScriptedClock clock = new ScriptedClock();
    private final AgentSeasonLifecycleService service = new AgentSeasonLifecycleService(currentMember, seasons,
            accounts, clock);
    private final Season season = new Season(AGENT, 12, 7L, "test", LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 1, 2), new BigDecimal("10000000"), new BigDecimal("0.00015000"),
            new BigDecimal("0.00025000"), new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"),
            new Formation(25, 25, 25, 25));

    AgentSeasonLifecycleServiceTest() {
        ReflectionTestUtils.setField(season, "id", SEASON);
        when(currentMember.existingId()).thenReturn(Optional.of(AGENT));
        when(seasons.findByIdAndMemberIdForUpdate(SEASON, AGENT)).thenReturn(Optional.of(season));
        CashAccount account = new CashAccount(SEASON);
        account.applyLedgerAmount(new BigDecimal("10000000"));
        when(accounts.findById(SEASON)).thenReturn(Optional.of(account));
    }

    private static void code(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    private void pausedAt(Instant when) {
        clock.script(when);
        service.pause(AGENT, SEASON);
        assertThat(season.getStatus()).isEqualTo(SeasonStatus.PAUSED);
    }

    @Test
    void anotherAgentsPathIsNotFoundForEveryOperation() {
        code(() -> service.pause(AGENT + 1, SEASON), ErrorCode.NOT_FOUND);
        code(() -> service.resume(AGENT + 1, SEASON), ErrorCode.NOT_FOUND);
        code(() -> service.voidSeason(AGENT + 1, SEASON, new VoidSeasonRequest(VoidReason.MODEL_GONE, "x")),
                ErrorCode.NOT_FOUND);
        code(() -> service.requireActing(AGENT + 1), ErrorCode.NOT_FOUND);
        assertThat(season.getStatus()).isEqualTo(SeasonStatus.IN_PROGRESS);
    }

    @Test
    void aSeasonOfAnotherAgentIsNotFound() {
        when(seasons.findByIdAndMemberIdForUpdate(SEASON + 1, AGENT)).thenReturn(Optional.empty());

        code(() -> service.pause(AGENT, SEASON + 1), ErrorCode.NOT_FOUND);
    }

    @Test
    void pauseThenResumeReturnsTheSameTurn() {
        season.advanceTurn();
        clock.script(T0);

        SeasonDto paused = service.pause(AGENT, SEASON);

        assertThat(paused.status()).isEqualTo(SeasonStatus.PAUSED);
        assertThat(paused.currentTurnNo()).isEqualTo(1);
        assertThat(season.getPausedAt()).isEqualTo(T0);

        clock.script(T0.plus(Duration.ofDays(1)));
        SeasonDto resumed = service.resume(AGENT, SEASON);

        assertThat(resumed.status()).isEqualTo(SeasonStatus.IN_PROGRESS);
        assertThat(resumed.currentTurnNo()).isEqualTo(1);
        assertThat(season.getPausedAt()).isNull();
    }

    @Test
    void aRepeatedPauseKeepsTheFirstPauseTime() {
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofDays(2)));

        SeasonDto again = service.pause(AGENT, SEASON);

        assertThat(again.status()).isEqualTo(SeasonStatus.PAUSED);
        assertThat(season.getPausedAt()).as("a repeat must not restart the 7 days").isEqualTo(T0);
    }

    @Test
    void aRepeatedResumeOnARunningSeasonChangesNothing() {
        SeasonDto dto = service.resume(AGENT, SEASON);

        assertThat(dto.status()).isEqualTo(SeasonStatus.IN_PROGRESS);
    }

    @Test
    void resumeAtSixDaysTwentyThreeHoursStillResumes() {
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofHours(6 * 24 + 23)));

        SeasonDto dto = service.resume(AGENT, SEASON);

        assertThat(dto.status()).isEqualTo(SeasonStatus.IN_PROGRESS);
        assertThat(season.getAbandonedTurnNo()).isNull();
    }

    @Test
    void resumeAtExactlySevenDaysBecomesAnAbandonAtTheSameTurn() {
        season.advanceTurn();
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofDays(7)));

        SeasonDto dto = service.resume(AGENT, SEASON);

        assertThat(dto.status()).isEqualTo(SeasonStatus.ABANDONING);
        assertThat(dto.abandonedTurnNo()).isEqualTo(1);
        assertThat(dto.notStarted()).as("an agent season has no 3-turn exception").isFalse();
        assertThat(season.getPausedAt()).isNull();
    }

    @Test
    void aPauseRepeatedAfterSevenDaysBecomesAnAbandonToo() {
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofDays(9)));

        assertThat(service.pause(AGENT, SEASON).status()).isEqualTo(SeasonStatus.ABANDONING);
    }

    @Test
    void pauseAndResumeAreRefusedWhereThereIsNothingToPauseOrResume() {
        season.abandon();
        code(() -> service.pause(AGENT, SEASON), ErrorCode.SEASON_NOT_ACTIVE);
        code(() -> service.resume(AGENT, SEASON), ErrorCode.SEASON_NOT_ACTIVE);

        Season halted = new Season(AGENT, 12, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
        halted.halt();
        when(seasons.findByIdAndMemberIdForUpdate(SEASON + 1, AGENT)).thenReturn(Optional.of(halted));
        code(() -> service.pause(AGENT, SEASON + 1), ErrorCode.SEASON_HALTED);
        code(() -> service.resume(AGENT, SEASON + 1), ErrorCode.SEASON_HALTED);
    }

    @Test
    void voidIsTerminalKeepsTheTurnAndIsRefusedForAFinishedSeason() {
        season.advanceTurn();
        VoidSeasonRequest request = new VoidSeasonRequest(VoidReason.ENGINE_DEFECT, "  ledger replay differs  ");

        SeasonDto dto = service.voidSeason(AGENT, SEASON, request);

        assertThat(dto.status()).isEqualTo(SeasonStatus.VOID);
        assertThat(dto.currentTurnNo()).isEqualTo(1);
        assertThat(season.getVoidReason()).isEqualTo(VoidReason.ENGINE_DEFECT);
        assertThat(season.getVoidNote()).as("the note is stripped").isEqualTo("ledger replay differs");

        assertThat(service.voidSeason(AGENT, SEASON, request).status()).as("same reason: a repeat")
                .isEqualTo(SeasonStatus.VOID);
        code(() -> service.voidSeason(AGENT, SEASON, new VoidSeasonRequest(VoidReason.MODEL_GONE, "x")),
                ErrorCode.SEASON_NOT_ACTIVE);
        code(() -> service.pause(AGENT, SEASON), ErrorCode.SEASON_NOT_ACTIVE);
        code(() -> service.resume(AGENT, SEASON), ErrorCode.SEASON_NOT_ACTIVE);
    }

    @Test
    void aFinishedSeasonCannotBeVoided() {
        for (int i = 0; i < 12; i++) {
            season.advanceTurn();
        }

        code(() -> service.voidSeason(AGENT, SEASON, new VoidSeasonRequest(VoidReason.MODEL_GONE, "x")),
                ErrorCode.SEASON_NOT_ACTIVE);
        assertThat(season.getStatus()).isEqualTo(SeasonStatus.FINISHED);
    }

    @Test
    void aPausedSeasonCanBeVoided() {
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofDays(1)));

        assertThat(service.voidSeason(AGENT, SEASON, new VoidSeasonRequest(VoidReason.MODEL_GONE, "gone")).status())
                .isEqualTo(SeasonStatus.VOID);
        assertThat(season.getPausedAt()).isNull();
    }

    @Test
    void anOverduePauseCannotBeVoidedAtExactlySevenDaysButCanAtSixDaysTwentyThreeHours() {
        pausedAt(T0);
        clock.script(T0.plus(Duration.ofHours(6 * 24 + 23)));
        assertThat(service.voidSeason(AGENT, SEASON, new VoidSeasonRequest(VoidReason.MODEL_GONE, "gone")).status())
                .isEqualTo(SeasonStatus.VOID);

        Season other = newSeason();
        when(seasons.findByIdAndMemberIdForUpdate(SEASON + 1, AGENT)).thenReturn(Optional.of(other));
        other.pause(T0);
        clock.script(T0.plus(Duration.ofDays(7)));

        code(() -> service.voidSeason(AGENT, SEASON + 1, new VoidSeasonRequest(VoidReason.MODEL_GONE, "gone")),
                ErrorCode.SEASON_NOT_ACTIVE);
        assertThat(other.getVoidReason()).isNull();
    }

    @Test
    void anAbandoningSeasonCannotBeVoidedButAHaltedOneCan() {
        season.abandon();
        code(() -> service.voidSeason(AGENT, SEASON, new VoidSeasonRequest(VoidReason.ENGINE_DEFECT, "x")),
                ErrorCode.SEASON_NOT_ACTIVE);
        assertThat(season.getStatus()).isEqualTo(SeasonStatus.ABANDONING);

        Season halted = newSeason();
        ReflectionTestUtils.setField(halted, "id", SEASON);
        halted.halt();
        when(seasons.findByIdAndMemberIdForUpdate(SEASON + 1, AGENT)).thenReturn(Optional.of(halted));
        assertThat(service.voidSeason(AGENT, SEASON + 1, new VoidSeasonRequest(VoidReason.ENGINE_DEFECT, "x")).status())
                .isEqualTo(SeasonStatus.VOID);
    }

    private static Season newSeason() {
        return new Season(AGENT, 12, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
    }
}
