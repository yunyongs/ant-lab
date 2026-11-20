package antlab.league;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.member.CurrentMember;
import antlab.member.MemberKind;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The league table for the league's owner. Read only: it shows the seats as they are, adds the valuation multiple of
 * seats that finished, and ranks them only once the league is closed. A league that is not the caller's answers the same
 * as a missing one.
 */
@Service
public class LeagueTableQuery {

    private final CurrentMember currentMember;
    private final LeagueRepository leagues;
    private final LeagueEntryRepository entries;
    private final Clock clock;

    public LeagueTableQuery(CurrentMember currentMember, LeagueRepository leagues, LeagueEntryRepository entries,
            Clock clock) {
        this.currentMember = currentMember;
        this.leagues = leagues;
        this.entries = entries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LeagueTableDto get(long leagueId) {
        long me = currentMember.existingId().orElseThrow(LeagueTableQuery::notFound);
        League league = leagues.findById(leagueId).filter(l -> l.getOwnerMemberId().equals(me))
                .orElseThrow(LeagueTableQuery::notFound);
        List<LeagueEntryRow> rows = entries.findRowsByLeagueId(leagueId);
        List<Long> seasonIds = rows.stream().map(LeagueEntryRow::seasonId).toList();
        Map<Long, LeagueAiRow> ai = seasonIds.isEmpty() ? Map.of()
                : entries.findAiRowsBySeasonIds(seasonIds).stream()
                        .collect(Collectors.toMap(LeagueAiRow::seasonId, Function.identity()));
        boolean closed = league.getStatus() == LeagueStatus.CLOSED;
        // While the league is open no multiple is shown, so a finished seat cannot hint at the path to a seat still playing.
        Map<Long, BigDecimal> finals = seasonIds.isEmpty() || !closed ? Map.of()
                : entries.findFinalValues(seasonIds).stream()
                        .collect(Collectors.toMap(LeagueFinalValueRow::seasonId, LeagueFinalValueRow::totalValue));
        Set<Long> agentOwners = new HashSet<>();
        ai.values().forEach(a -> agentOwners.add(a.ownerMemberId()));

        Instant now = clock.instant();
        Map<Long, BigDecimal> multiples = new HashMap<>();
        for (LeagueEntryRow r : rows) {
            BigDecimal finalValue = finals.get(r.seasonId());
            if (finalValue != null && r.season().getStatus() == SeasonStatus.FINISHED) {
                multiples.put(r.seasonId(), MoneyMath.ratio(finalValue, r.season().getInitialCash()));
            }
        }
        List<LeagueTableEntryDto> seats = rows.stream().map(r -> {
            LeagueAiRow a = ai.get(r.seasonId());
            BigDecimal multiple = multiples.get(r.seasonId());
            boolean owns = r.memberKind() == MemberKind.HUMAN && agentOwners.contains(r.memberId());
            return new LeagueTableEntryDto(r.memberId(), r.memberKind(), r.memberName(), r.seasonId(),
                    r.season().effectiveStatus(now), r.season().getCurrentTurnNo(),
                    a == null ? null : new LeagueTableAiDto(a.personaKey(), a.personaVersion(), a.modelRoute(),
                            a.modelId(), a.memoryVersion()),
                    owns, multiple, closed && multiple != null ? rank(multiple, multiples.values()) : null);
        }).toList();
        return new LeagueTableDto(league.getId(), league.getName(), league.getStatus(), league.getPathLength(),
                league.getMaxEntries(), seats);
    }

    /** One plus the number of seats strictly ahead, so ties share the better rank. */
    private static int rank(BigDecimal multiple, Iterable<BigDecimal> all) {
        int ahead = 0;
        for (BigDecimal other : all) {
            if (other.compareTo(multiple) > 0) {
                ahead++;
            }
        }
        return ahead + 1;
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
