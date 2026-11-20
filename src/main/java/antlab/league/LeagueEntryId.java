package antlab.league;

import java.io.Serializable;

/** Key of {@link LeagueEntry}: the league and the season in it. */
public record LeagueEntryId(Long leagueId, Long seasonId) implements Serializable {
}
