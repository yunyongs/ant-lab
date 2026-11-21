package antlab.league;

import antlab.agent.AgentProfile;
import antlab.agent.AgentProfileRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonDto;
import antlab.season.SeasonPathRepository;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStartService;
import antlab.season.SeasonStartService.FixedPath;
import antlab.season.StartSeasonRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a season, inside a league when the request names one. The league entry is the normal season start with the
 * league's stored seed, so every seat plays the same path; the checks below make an unfair entry fail instead of start.
 *
 * <p>Lock order, on every path that takes both: member row first ({@link CurrentMember#ensureId()}), then the league
 * row. {@code LeagueService.create} locks the owner member before it inserts the league, and {@code close} locks only
 * the league row, so no path takes the league row before a member row and two requests cannot wait on each other.
 */
@Service
public class SeasonEntryService {

    private final CurrentMember currentMember;
    private final SeasonStartService starts;
    private final LeagueRepository leagues;
    private final LeagueEntryRepository entries;
    private final AgentProfileRepository profiles;
    private final SeasonRepository seasons;
    private final SeasonPathRepository paths;

    public SeasonEntryService(CurrentMember currentMember, SeasonStartService starts, LeagueRepository leagues,
            LeagueEntryRepository entries, AgentProfileRepository profiles, SeasonRepository seasons,
            SeasonPathRepository paths) {
        this.currentMember = currentMember;
        this.starts = starts;
        this.leagues = leagues;
        this.entries = entries;
        this.profiles = profiles;
        this.seasons = seasons;
        this.paths = paths;
    }

    @Transactional
    public SeasonDto start(StartSeasonRequest request) {
        if (request.leagueId() == null) {
            return starts.start(request);
        }
        long memberId = currentMember.ensureId();
        League league = leagues.findByIdForUpdate(request.leagueId())
                .filter(l -> l.getOwnerMemberId().equals(ownerOf(memberId)))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다"));
        if (league.getStatus() == LeagueStatus.CLOSED) {
            throw new DomainException(ErrorCode.LEAGUE_CLOSED, "닫힌 리그에는 참가할 수 없다");
        }
        if (request.pathLength() != null && request.pathLength() != league.getPathLength()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "경로 길이는 리그가 정한다 — 생략하거나 리그와 같아야 한다");
        }
        if (entries.existsByLeagueIdAndMemberId(league.getId(), memberId)) {
            throw new DomainException(ErrorCode.LEAGUE_SEAT_TAKEN, "이 리그에 이미 자리가 있다");
        }
        if (entries.countByLeagueId(league.getId()) >= league.getMaxEntries()) {
            throw new DomainException(ErrorCode.LEAGUE_FULL, "리그의 자리가 모두 찼다");
        }

        SeasonDto started = starts.start(request, new FixedPath(league.getSeed(), league.getPathLength()));
        Season season = seasons.findById(started.id()).orElseThrow();
        requireSameSettings(league, season);
        requireSamePath(league, season);
        entries.save(new LeagueEntry(league.getId(), season.getId(), memberId));
        return started;
    }

    /** A human is its own owner; an agent belongs to the member that registered it. */
    private Long ownerOf(long memberId) {
        return profiles.findById(memberId).map(AgentProfile::getOwnerMemberId).orElse(memberId);
    }

    /** The season row is compared with the league row, so a changed pool date, snapshot, settings or engine fails the entry. */
    private static void requireSameSettings(League league, Season season) {
        boolean same = league.getEngineVersion().equals(season.getEngineVersion())
                && league.getBaseDate().equals(season.getBaseDate())
                && league.getDataSnapshotDate().equals(season.getDataSnapshotDate())
                && league.getPathLength() == season.getPathLength()
                && league.getSeed() == season.getSeed()
                && league.getInitialCash().compareTo(season.getInitialCash()) == 0
                && league.getFeeRateKr().compareTo(season.getFeeRateKr()) == 0
                && league.getFeeRateUs().compareTo(season.getFeeRateUs()) == 0
                && league.getFxSpreadRate().compareTo(season.getFxSpreadRate()) == 0;
        if (!same) {
            throw new DomainException(ErrorCode.LEAGUE_STALE, "시즌 설정이 리그와 달라졌다");
        }
    }

    /** The new season must name the same month block in every turn as the league's first seat; only a count is read. */
    private void requireSamePath(League league, Season season) {
        entries.findFirstByLeagueIdOrderBySeasonId(league.getId()).ifPresent(first -> {
            if (paths.countSharedTurns(season.getId(), first.getSeasonId()) != league.getPathLength()) {
                throw new DomainException(ErrorCode.LEAGUE_STALE, "경로가 리그의 첫 참가와 다르다");
            }
        });
    }
}
