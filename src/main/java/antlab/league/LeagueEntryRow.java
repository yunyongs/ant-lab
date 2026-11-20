package antlab.league;

import antlab.member.MemberKind;
import antlab.season.Season;

/** One seat as the query returns it; the service turns it into {@link LeagueEntryDto}. */
public record LeagueEntryRow(Long seasonId, Long memberId, MemberKind memberKind, String memberName, Season season) {
}
