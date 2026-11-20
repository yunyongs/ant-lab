package antlab.api.agentrunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import antlab.account.CashAccountRepository;
import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonLifecycleService;
import antlab.agent.AgentSeasonService;
import antlab.agent.AgentTurnViewService;
import antlab.agent.decision.AgentDecisionService;
import antlab.agent.decision.AgentTurnAdvanceService;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.AbandonSeasonRequest;
import antlab.season.SeasonAbandonService;
import antlab.season.SeasonRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The abandon route checks that the path's agent is the acting one before the shared abandon service runs. */
class AgentRunnerAbandonRouteTest {

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SeasonAbandonService abandons = mock(SeasonAbandonService.class);
    private final AgentRunnerController controller = new AgentRunnerController(mock(AgentRegistrationService.class),
            mock(AgentSeasonService.class), new AgentSeasonLifecycleService(currentMember, mock(SeasonRepository.class),
                    mock(CashAccountRepository.class), Clock.systemUTC()),
            abandons, mock(AgentTurnViewService.class), mock(AgentDecisionService.class),
            mock(AgentTurnAdvanceService.class));

    @Test
    void aPathAgentOtherThanTheActingOneIsNotFoundAndNothingIsAbandoned() {
        when(currentMember.existingId()).thenReturn(Optional.of(7L));

        assertThatThrownBy(() -> controller.abandon(8L, 70L, new AbandonSeasonRequest(0)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(abandons);
    }

    @Test
    void theActingAgentReachesTheAbandonService() {
        when(currentMember.existingId()).thenReturn(Optional.of(7L));

        controller.abandon(7L, 70L, new AbandonSeasonRequest(3));

        verify(abandons).abandon(70L, 3);
        org.mockito.Mockito.verify(abandons, org.mockito.Mockito.times(1)).abandon(anyLong(), anyInt());
    }
}
