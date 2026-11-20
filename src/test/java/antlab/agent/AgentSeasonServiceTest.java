package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.FormationDto;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStartService;
import antlab.season.SeasonStatus;
import antlab.support.ScriptedClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The checks of the agent season start that sit before the human start path, without Spring or a database. */
class AgentSeasonServiceTest {

    private static final long AGENT = 7L;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final SeasonAgentConfigRepository configs = mock(SeasonAgentConfigRepository.class);
    private final SeasonStartService starts = mock(SeasonStartService.class);
    private final SeasonRepository seasons = mock(SeasonRepository.class);
    private final PersonaVersionService personas = mock(PersonaVersionService.class);
    private final ScriptedClock clock = new ScriptedClock();
    private final AgentSeasonService service = new AgentSeasonService(currentMember, profiles, configs, starts, seasons,
            new AgentRunnerProperties("x".repeat(32), 1L, 5), personas, clock);

    private static StartAgentSeasonRequest request(String modelRequested) {
        return new StartAgentSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null,
                new AgentSeasonConfigRequest(1, "a".repeat(64), "Value", modelRequested, new BigDecimal("0.7"), null, 2000,
                        60000, "p1", "r1", null, 100, new BigDecimal("1.5")));
    }

    private void agentExists() {
        when(profiles.findById(AGENT)).thenReturn(Optional.of(
                new AgentProfile(AGENT, 1L, "value-investor", ModelRoute.OPENROUTER, "fake/model-1")));
    }

    @Test
    void a_path_id_other_than_the_acting_agent_is_NOT_FOUND_even_when_that_agent_exists() {
        when(currentMember.ensureId()).thenReturn(AGENT + 1);
        agentExists();

        assertThatThrownBy(() -> service.start(AGENT, request("fake/model-1")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(starts, never()).start(any());
        verify(configs, never()).save(any());
    }

    @Test
    void a_model_other_than_the_registered_one_is_INVALID_ARGUMENT_and_starts_nothing() {
        when(currentMember.ensureId()).thenReturn(AGENT);
        agentExists();

        assertThatThrownBy(() -> service.start(AGENT, request("fake/model-2")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        verify(starts, never()).start(any());
        verify(configs, never()).save(any());
    }

    @Test
    void the_weekly_window_counts_a_season_created_exactly_seven_days_ago() {
        when(currentMember.ensureId()).thenReturn(AGENT);
        agentExists();
        clock.script(NOW);
        // the repository method is inclusive (>=), and it is asked for exactly now minus 7 days
        when(seasons.countByMemberIdAndCreatedAtGreaterThanEqual(AGENT, NOW.minus(Duration.ofDays(7)))).thenReturn(5L);

        assertThatThrownBy(() -> service.start(AGENT, request("fake/model-1")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SEASON_LIMIT));
        verify(starts, never()).start(any());
    }

    @Test
    void aPausedOrAbandoningSeasonBlocksANewSeasonBeforeAnythingIsStarted() {
        when(currentMember.ensureId()).thenReturn(AGENT);
        agentExists();
        when(seasons.countByMemberIdAndStatusIn(AGENT, List.of(SeasonStatus.PAUSED, SeasonStatus.ABANDONING)))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.start(AGENT, request("fake/model-1")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SEASON_LIMIT));
        verify(starts, never()).start(any());
        verify(configs, never()).save(any());
    }

    @Test
    void whenNoSeasonIsPausedOrAbandoningTheStartGoesOn() {
        when(currentMember.ensureId()).thenReturn(AGENT);
        agentExists();
        when(starts.start(any())).thenThrow(new IllegalStateException("reached the start"));

        assertThatThrownBy(() -> service.start(AGENT, request("fake/model-1")))
                .isInstanceOf(IllegalStateException.class).hasMessage("reached the start");
    }
}
