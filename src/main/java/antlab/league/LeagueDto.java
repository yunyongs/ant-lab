package antlab.league;

import java.util.List;

/** A league as the runner sees it. The seed, dates, engine version and money settings are never in it. */
public record LeagueDto(
        long id,
        String name,
        LeagueStatus status,
        int pathLength,
        int maxEntries,
        List<LeagueEntryDto> entries) {
}
