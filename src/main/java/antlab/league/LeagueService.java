package antlab.league;

import antlab.agent.AgentRunnerProperties;
import antlab.asset.AssetPosition;
import antlab.asset.AssetPositionRepository;
import antlab.asset.PoolProperties;
import antlab.asset.PoolReadiness;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.engine.GeneratedPath;
import antlab.engine.PathRequest;
import antlab.engine.PathSource;
import antlab.member.Member;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import antlab.season.SeasonProperties;
import antlab.season.SeasonStatus;
import antlab.season.SeedGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, read and close a league for the owner the runner is configured with. The league fixes the seed, the path
 * length and the season settings once; the seed leaves the server in no response.
 */
@Service
public class LeagueService {

    private static final int NAME_MAX = 50;
    private static final List<SeasonStatus> SETTLED = List.of(SeasonStatus.FINISHED, SeasonStatus.VOID);

    private final AgentRunnerProperties runner;
    private final MemberRepository members;
    private final LeagueRepository leagues;
    private final LeagueEntryRepository entries;
    private final AssetPositionRepository positions;
    private final PoolProperties poolProperties;
    private final SeasonProperties seasonProperties;
    private final PathSource pathSource;
    private final SeedGenerator seedGenerator;
    private final Clock clock;

    public LeagueService(AgentRunnerProperties runner, MemberRepository members, LeagueRepository leagues,
            LeagueEntryRepository entries, AssetPositionRepository positions, PoolProperties poolProperties,
            SeasonProperties seasonProperties, PathSource pathSource, SeedGenerator seedGenerator, Clock clock) {
        this.runner = runner;
        this.members = members;
        this.leagues = leagues;
        this.entries = entries;
        this.positions = positions;
        this.poolProperties = poolProperties;
        this.seasonProperties = seasonProperties;
        this.pathSource = pathSource;
        this.seedGenerator = seedGenerator;
        this.clock = clock;
    }

    @Transactional
    public LeagueDto create(CreateLeagueRequest request) {
        long ownerId = ownerId();
        Member owner = members.findByIdForUpdate(ownerId).orElseThrow(LeagueService::unauthenticated);
        if (owner.getKind() != MemberKind.HUMAN) {
            throw unauthenticated();
        }
        String name = checkedName(request.name());
        LocalDate baseDate = poolProperties.baseDate();
        List<AssetPosition> pool = positions.findByBaseDate(baseDate);
        PoolReadiness.require(pool, p -> p.getSystemPosition() == null);

        // The path is generated once to learn the engine version, and before any row: a refused length leaves nothing.
        int length = request.pathLengthOrDefault();
        long seed = seedGenerator.nextSeed();
        GeneratedPath path = pathSource.generate(new PathRequest(baseDate, length, seed));
        League league = leagues.save(new League(ownerId, name, seed, length, baseDate,
                path.engineVersion(), seasonProperties.dataSnapshotDate(),
                MoneyMath.exactWon(seasonProperties.initialCash()), seasonProperties.feeRateKr(),
                seasonProperties.feeRateUs(), seasonProperties.fxSpreadRate(), request.maxEntriesOrDefault()));
        return toDto(league, List.of());
    }

    @Transactional(readOnly = true)
    public LeagueDto get(long leagueId) {
        League league = leagues.findById(leagueId).filter(this::ownedByRunnerOwner)
                .orElseThrow(LeagueService::notFound);
        return toDto(league, entries.findRowsByLeagueId(leagueId));
    }

    /**
     * Stops new entries once every seat is settled (FINISHED or VOID). A closed league answers as it is, so a repeated
     * close is not an error. The league row is locked first, so a close cannot run between an entry's checks and its insert.
     */
    @Transactional
    public LeagueDto close(long leagueId) {
        ownerId();
        League league = leagues.findByIdForUpdate(leagueId).filter(this::ownedByRunnerOwner)
                .orElseThrow(LeagueService::notFound);
        List<LeagueEntryRow> rows = entries.findRowsByLeagueId(leagueId);
        if (league.getStatus() == LeagueStatus.CLOSED) {
            return toDto(league, rows);
        }
        Instant now = clock.instant();
        boolean unsettled = rows.stream().anyMatch(r -> !SETTLED.contains(r.season().effectiveStatus(now)));
        if (unsettled) {
            throw new DomainException(ErrorCode.LEAGUE_NOT_SETTLED, "끝나지 않은 참가가 있어 리그를 닫을 수 없다");
        }
        league.close(now);
        return toDto(league, rows);
    }

    /** The name as stored: stripped first, then 1 to 50 characters with no control character (tab, newline, ...). */
    private static String checkedName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "리그 이름은 제어 문자 없는 1~50자다");
        }
        return name;
    }

    private LeagueDto toDto(League league, List<LeagueEntryRow> rows) {
        Instant now = clock.instant();
        List<LeagueEntryDto> seats = rows.stream()
                .map(r -> new LeagueEntryDto(r.memberId(), r.memberKind(), r.memberName(), r.seasonId(),
                        r.season().effectiveStatus(now), r.season().getCurrentTurnNo()))
                .toList();
        return new LeagueDto(league.getId(), league.getName(), league.getStatus(), league.getPathLength(),
                league.getMaxEntries(), seats);
    }

    private long ownerId() {
        if (!runner.ownerConfigured()) {
            throw unauthenticated();
        }
        return runner.ownerMemberId();
    }

    /** Another owner's league and a missing one answer the same, so the caller cannot tell them apart. */
    private boolean ownedByRunnerOwner(League league) {
        return runner.ownerConfigured() && league.getOwnerMemberId().equals(runner.ownerMemberId());
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }

    private static DomainException unauthenticated() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "인증이 필요하다");
    }
}
