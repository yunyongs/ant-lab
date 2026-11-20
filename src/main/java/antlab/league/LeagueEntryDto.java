package antlab.league;

import antlab.member.MemberKind;
import antlab.season.SeasonStatus;

/**
 * One seat of a league as the runner sees it. {@code status} is the effective season status (an overdue pause already
 * reads as an abandon). It carries no seed, date or engine field.
 */
public record LeagueEntryDto(
        long memberId,
        MemberKind memberKind,
        String displayName,
        long seasonId,
        SeasonStatus status,
        int currentTurnNo) {
}
