package antlab.agent.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import antlab.agent.AgentCallRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Formation;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.VoidReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A paused season can resume, so the guard answers it itself: leaving it to the turn path would let a resume between the
 * check and the facade skip the decision check. A void season never returns, so it may pass to the turn path.
 */
class AgentDecisionGuardPausedTest {

    private static final long AGENT = 7L;
    private static final long SEASON = 11L;

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SeasonRepository seasons = mock(SeasonRepository.class);
    private final AgentCallRepository calls = mock(AgentCallRepository.class);
    private final AgentDecisionGuard guard = new AgentDecisionGuard(currentMember, seasons, calls);

    private Season season() {
        Season s = new Season(AGENT, 3, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
        when(currentMember.existingId()).thenReturn(Optional.of(AGENT));
        when(seasons.findByIdAndMemberId(SEASON, AGENT)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void aPausedSeasonIsAnsweredWithSeasonNotActiveWithoutLookingAtDecisions() {
        season().pause(Instant.parse("2026-11-01T00:00:00Z"));

        assertThatThrownBy(() -> guard.requireDecision(AGENT, SEASON, 0)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SEASON_NOT_ACTIVE));
        verifyNoInteractions(calls);
    }

    @Test
    void aVoidSeasonPassesToTheTurnPathWhichRefusesIt() {
        season().voidSeason(VoidReason.MODEL_GONE, "gone");

        guard.requireDecision(AGENT, SEASON, 0);

        verifyNoInteractions(calls);
    }
}
