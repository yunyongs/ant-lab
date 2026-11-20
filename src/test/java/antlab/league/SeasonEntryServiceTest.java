package antlab.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import antlab.agent.AgentProfileRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.FormationDto;
import antlab.season.SeasonPathRepository;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStartService;
import antlab.season.StartSeasonRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The order of the two row locks and the checks that sit before the season start, with mocks. */
class SeasonEntryServiceTest {

    private static final long MEMBER = 5L;
    private static final long LEAGUE = 50L;

    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SeasonStartService starts = mock(SeasonStartService.class);
    private final LeagueRepository leagues = mock(LeagueRepository.class);
    private final LeagueEntryRepository entries = mock(LeagueEntryRepository.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final SeasonEntryService service = new SeasonEntryService(currentMember, starts, leagues, entries, profiles,
            mock(SeasonRepository.class), mock(SeasonPathRepository.class));

    private static StartSeasonRequest request(Long league) {
        return new StartSeasonRequest(new BigDecimal("-0.20"), new FormationDto(10, 30, 30, 30), null, league);
    }

    private League league(LeagueStatus status) {
        League league = new League(MEMBER, "L", 7L, 12, LocalDate.of(2026, 1, 2), "test", LocalDate.of(2026, 1, 2),
                new BigDecimal("10000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00025000"),
                new BigDecimal("0.00400000"), 8);
        if (status == LeagueStatus.CLOSED) {
            league.close(java.time.Instant.parse("2026-01-03T00:00:00Z"));
        }
        return league;
    }

    @Test
    void the_member_row_is_locked_before_the_league_row_and_the_league_row_is_locked_once() {
        AtomicBoolean memberLocked = new AtomicBoolean();
        when(currentMember.ensureId()).thenAnswer(call -> {
            memberLocked.set(true);
            return MEMBER;
        });
        when(leagues.findByIdForUpdate(LEAGUE)).thenAnswer(call -> {
            assertThat(memberLocked).as("the member row is already locked").isTrue();
            return Optional.of(league(LeagueStatus.CLOSED));
        });

        assertThatThrownBy(() -> service.start(request(LEAGUE)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LEAGUE_CLOSED));

        verify(leagues, times(1)).findByIdForUpdate(LEAGUE);
        verify(starts, never()).start(any(), any());
    }

    @Test
    void a_request_without_a_league_takes_no_league_lock_and_starts_the_plain_way() {
        service.start(request(null));

        verify(starts).start(any(StartSeasonRequest.class));
        verify(leagues, never()).findByIdForUpdate(any());
    }
}
