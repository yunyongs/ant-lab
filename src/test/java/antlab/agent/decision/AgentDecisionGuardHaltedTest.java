package antlab.agent.decision;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A halted season is answered by the guard itself, so a resume right after the check cannot skip the decision check. */
class AgentDecisionGuardHaltedTest {

    private static final long AGENT = 7L;
    private static final long SEASON = 11L;

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SeasonRepository seasons = mock(SeasonRepository.class);
    private final AgentCallRepository calls = mock(AgentCallRepository.class);
    private final AgentDecisionGuard guard = new AgentDecisionGuard(currentMember, seasons, calls);

    @Test
    void aHaltedSeasonIsAnsweredWithSeasonHaltedWithoutLookingAtDecisions() {
        Season halted = new Season(AGENT, 3, 7L, "test", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"), new Formation(25, 25, 25, 25));
        halted.halt();
        when(currentMember.existingId()).thenReturn(Optional.of(AGENT));
        when(seasons.findByIdAndMemberId(SEASON, AGENT)).thenReturn(Optional.of(halted));

        assertThatThrownBy(() -> guard.requireDecision(AGENT, SEASON, 0)).isInstanceOfSatisfying(DomainException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(ErrorCode.SEASON_HALTED));
        verifyNoInteractions(calls);
    }
}
