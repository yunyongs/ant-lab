package antlab.agent.memory;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may write a reflection and when. A season is eligible when it is FINISHED and was not abandoned (a VOID or
 * abandoned season is the record of a defect or an auto-hold, not of the agent's decisions). It is released when no league
 * of its same-seed group is still open, because the reflection would show the path the other seats still play. Among the
 * released eligible seasons without an outcome the earliest in finish order (the order the score service uses) goes first,
 * so the memory chain is written in one order.
 */
@Service
public class MemoryEligibility {

    private static final String PENDING = """
            SELECT s.id AS season_id,
                   EXISTS (SELECT 1
                           FROM league_entry e
                           JOIN league l ON l.id = e.league_id
                           JOIN league g ON g.id = COALESCE(l.seed_league_id, l.id)
                                         OR g.seed_league_id = COALESCE(l.seed_league_id, l.id)
                           WHERE e.season_id = s.id AND g.status = 'OPEN') AS held
            FROM season s
            JOIN turn t ON t.season_id = s.id AND t.turn_no = s.path_length
            WHERE s.member_id = :agentId AND s.status = 'FINISHED' AND s.abandoned_turn_no IS NULL
              AND NOT EXISTS (SELECT 1 FROM agent_reflection r WHERE r.season_id = s.id)
            ORDER BY t.created_at, s.id""";

    /** Seasons waiting for an outcome: the released ones in finish order, and the ones an open league or group holds. */
    public record Pending(List<Long> released, List<Long> held) {
        public int heldCount() {
            return held.size();
        }
    }

    private final JdbcClient jdbc;
    private final SeasonRepository seasons;
    private final AgentReflectionRepository reflections;

    public MemoryEligibility(JdbcClient jdbc, SeasonRepository seasons, AgentReflectionRepository reflections) {
        this.jdbc = jdbc;
        this.seasons = seasons;
        this.reflections = reflections;
    }

    @Transactional(readOnly = true)
    public Pending pending(long agentId) {
        List<Long> released = new ArrayList<>();
        List<Long> held = new ArrayList<>();
        jdbc.sql(PENDING).param("agentId", agentId).query((rs, n) -> {
            (rs.getBoolean("held") ? held : released).add(rs.getLong("season_id"));
            return null;
        }).list();
        return new Pending(List.copyOf(released), List.copyOf(held));
    }

    /**
     * Checks that the agent's season may get its reflection now, in a fixed order: the season is the agent's (404), it is
     * FINISHED (409), eligible (409), has no outcome yet (409), its league and group are closed (409), and no earlier
     * released season is still waiting (409). Returns the season.
     */
    @Transactional(readOnly = true)
    public Season requireReflectable(long agentId, long seasonId) {
        Season season = seasons.findByIdAndMemberId(seasonId, agentId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다"));
        if (season.getStatus() == SeasonStatus.VOID) {
            throw new DomainException(ErrorCode.MEMORY_NOT_ELIGIBLE, "무효 시즌은 복기를 쓰지 않는다");
        }
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝난 시즌만 복기를 쓸 수 있다");
        }
        if (season.getAbandonedTurnNo() != null) {
            throw new DomainException(ErrorCode.MEMORY_NOT_ELIGIBLE, "포기한 시즌은 복기를 쓰지 않는다");
        }
        if (reflections.existsBySeasonId(seasonId)) {
            throw new DomainException(ErrorCode.MEMORY_DONE, "이 시즌의 복기 결과는 이미 기록되었다");
        }
        Pending pending = pending(agentId);
        if (pending.held().contains(seasonId)) {
            throw new DomainException(ErrorCode.LEAGUE_OPEN, "리그나 같은 시드 묶음이 닫힐 때까지 복기를 쓸 수 없다");
        }
        if (!pending.released().contains(seasonId)) {
            // FINISHED, not abandoned and without an outcome, yet neither held nor released: the season has no final turn row
            throw new IllegalStateException("finished season has no final turn: " + seasonId);
        }
        if (!pending.released().get(0).equals(seasonId)) {
            throw new DomainException(ErrorCode.MEMORY_ORDER, "먼저 끝난 시즌의 복기부터 써야 한다");
        }
        return season;
    }
}
