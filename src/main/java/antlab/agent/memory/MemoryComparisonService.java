package antlab.agent.memory;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.member.CurrentMember;
import antlab.scoring.SeasonScore;
import antlab.scoring.SeasonScoreDto;
import antlab.scoring.SeasonScoreRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares two memory versions of the agent on the same seeds. A group is a root league and its siblings; it counts only
 * when every league of it is CLOSED, which is also when the seasons are released for review and memory. In such a group the
 * agent may hold one finished season per league, so two seasons with different pinned versions played one seed. The report
 * reads stored rows only and answers with an opaque group label: no seed, date or month block.
 *
 * <p>Seasons that were abandoned are left out: after the abandon turn the record is an auto-hold, not the agent's decisions.
 * The same version on both sides (a control pair) takes the first two seasons of that version in the group.
 */
@Service
public class MemoryComparisonService {

    /** From this many groups on the report no longer calls its numbers anecdotal. */
    static final int ANECDOTAL_BELOW = 5;

    private static final String ROWS = """
            SELECT COALESCE(l.seed_league_id, l.id) AS group_id, s.id AS season_id, c.memory_version, c.persona_version,
                   t.total_value, s.initial_cash,
                   (SELECT count(*) FROM agent_call a
                    WHERE a.season_id = s.id AND a.call_kind = 'TURN_DECISION' AND a.is_final AND a.outcome IN ('NO_RESPONSE', 'INVALID')) AS failed_turns,
                   EXISTS (SELECT 1 FROM season o
                           WHERE o.member_id = s.member_id AND o.seed = s.seed
                             AND o.id NOT IN (SELECT e2.season_id
                                              FROM league_entry e2 JOIN league l2 ON l2.id = e2.league_id
                                              WHERE COALESCE(l2.seed_league_id, l2.id) = COALESCE(l.seed_league_id, l.id))
                          ) AS experienced
            FROM league_entry e
            JOIN league l ON l.id = e.league_id
            JOIN season s ON s.id = e.season_id
            JOIN season_agent_config c ON c.season_id = s.id
            JOIN turn t ON t.season_id = s.id AND t.turn_no = s.path_length
            WHERE s.member_id = :agentId AND s.status = 'FINISHED' AND s.abandoned_turn_no IS NULL
              AND NOT EXISTS (SELECT 1 FROM league g
                              WHERE COALESCE(g.seed_league_id, g.id) = COALESCE(l.seed_league_id, l.id)
                                AND g.status <> 'CLOSED')
            ORDER BY group_id, s.id""";

    private record SeasonRow(long groupId, long seasonId, Integer memoryVersion, int personaVersion, BigDecimal multiple, int failedTurns,
            boolean experienced) {
    }

    private final CurrentMember currentMember;
    private final AgentMemoryRepository memories;
    private final SeasonScoreRepository scores;
    private final JdbcClient jdbc;

    public MemoryComparisonService(CurrentMember currentMember, AgentMemoryRepository memories,
            SeasonScoreRepository scores, JdbcClient jdbc) {
        this.currentMember = currentMember;
        this.memories = memories;
        this.scores = scores;
        this.jdbc = jdbc;
    }

    /** {@code 0} stands for no memory. A version the agent does not have is not found. */
    @Transactional(readOnly = true)
    public MemoryComparisonDto compare(long agentId, int versionA, int versionB) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
        requireVersion(agentId, versionA);
        requireVersion(agentId, versionB);
        List<SeasonRow> rows = jdbc.sql(ROWS).param("agentId", agentId).query((rs, n) -> new SeasonRow(
                rs.getLong("group_id"), rs.getLong("season_id"), (Integer) rs.getObject("memory_version"), rs.getInt("persona_version"),
                MoneyMath.ratio(rs.getBigDecimal("total_value"), rs.getBigDecimal("initial_cash")),
                rs.getInt("failed_turns"), rs.getBoolean("experienced"))).list();
        Map<Long, List<SeasonRow>> byGroup = rows.stream()
                .collect(Collectors.groupingBy(SeasonRow::groupId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, SeasonScore> stored = rows.isEmpty() ? Map.of()
                : scores.findBySeasonIds(rows.stream().map(SeasonRow::seasonId).toList()).stream()
                        .collect(Collectors.toMap(SeasonScore::getSeasonId, Function.identity()));

        List<MemoryComparisonDto.Group> groups = new ArrayList<>();
        List<SeasonScoreDto> scoresA = new ArrayList<>();
        List<SeasonScoreDto> scoresB = new ArrayList<>();
        List<BigDecimal> multiplesA = new ArrayList<>();
        List<BigDecimal> multiplesB = new ArrayList<>();
        int bWins = 0;
        for (List<SeasonRow> seasons : byGroup.values()) {
            SeasonRow a = pick(seasons, versionA, null);
            SeasonRow b = a == null ? null : pick(seasons, versionB, a.seasonId());
            if (a == null || b == null) {
                continue;
            }
            groups.add(new MemoryComparisonDto.Group("G" + (groups.size() + 1), side(a, stored), side(b, stored)));
            multiplesA.add(a.multiple());
            multiplesB.add(b.multiple());
            if (b.multiple().compareTo(a.multiple()) > 0) {
                bWins++;
            }
            scoresA.add(stored.containsKey(a.seasonId()) ? SeasonScoreDto.of(stored.get(a.seasonId())) : null);
            scoresB.add(stored.containsKey(b.seasonId()) ? SeasonScoreDto.of(stored.get(b.seasonId())) : null);
        }
        MemoryComparisonDto.MeanDiff meanDiff = groups.isEmpty() ? null : meanDiff(multiplesA, multiplesB, scoresA, scoresB);
        return new MemoryComparisonDto(versionA, versionB, List.copyOf(groups),
                new MemoryComparisonDto.Summary(groups.size(), bWins, groups.size() < ANECDOTAL_BELOW, meanDiff));
    }

    /** The first season of the group with this version (0 = none) other than {@code except}; the lowest season number. */
    private static SeasonRow pick(List<SeasonRow> seasons, int version, Long except) {
        return seasons.stream().filter(s -> except == null || s.seasonId() != except)
                .filter(s -> version == 0 ? s.memoryVersion() == null : s.memoryVersion() != null && s.memoryVersion() == version)
                .min(Comparator.comparingLong(SeasonRow::seasonId)).orElse(null);
    }

    private static MemoryComparisonDto.Side side(SeasonRow row, Map<Long, SeasonScore> stored) {
        SeasonScore score = stored.get(row.seasonId());
        return new MemoryComparisonDto.Side(row.memoryVersion(), row.personaVersion(), row.multiple(), score == null ? null : SeasonScoreDto.of(score),
                row.failedTurns(), row.experienced());
    }

    private static MemoryComparisonDto.MeanDiff meanDiff(List<BigDecimal> multiplesA, List<BigDecimal> multiplesB,
            List<SeasonScoreDto> a, List<SeasonScoreDto> b) {
        return new MemoryComparisonDto.MeanDiff(mean(pairs(multiplesA, multiplesB, Function.identity()), 8),
                mean(pairs(a, b, SeasonScoreDto::score), 2), mean(pairs(a, b, SeasonScoreDto::resultScore), 2),
                mean(pairs(a, b, SeasonScoreDto::processScore), 2), itemsMean(a, b));
    }

    private static SeasonScoreDto.Items itemsMean(List<SeasonScoreDto> a, List<SeasonScoreDto> b) {
        return new SeasonScoreDto.Items(item(a, b, SeasonScoreDto.Items::risk), item(a, b, SeasonScoreDto.Items::discipline),
                item(a, b, SeasonScoreDto.Items::diversification), item(a, b, SeasonScoreDto.Items::turnover),
                item(a, b, SeasonScoreDto.Items::consistency), item(a, b, SeasonScoreDto.Items::formation),
                item(a, b, SeasonScoreDto.Items::role), item(a, b, SeasonScoreDto.Items::noise),
                item(a, b, SeasonScoreDto.Items::nudge));
    }

    private static BigDecimal item(List<SeasonScoreDto> a, List<SeasonScoreDto> b,
            Function<SeasonScoreDto.Items, BigDecimal> field) {
        return mean(pairs(a, b, s -> field.apply(s.items())), 2);
    }

    /** B minus A for every row where both sides have a value. */
    private static <T> List<BigDecimal> pairs(List<T> a, List<T> b, Function<T, BigDecimal> value) {
        List<BigDecimal> diffs = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            BigDecimal va = a.get(i) == null ? null : value.apply(a.get(i));
            BigDecimal vb = b.get(i) == null ? null : value.apply(b.get(i));
            if (va != null && vb != null) {
                diffs.add(vb.subtract(va));
            }
        }
        return diffs;
    }

    private static BigDecimal mean(List<BigDecimal> diffs, int scale) {
        return MoneyMath.mean(diffs, scale);
    }

    private void requireVersion(long agentId, int version) {
        if (version < 0 || (version > 0 && !memories.existsByMemberIdAndVersion(agentId, version))) {
            throw notFound();
        }
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
