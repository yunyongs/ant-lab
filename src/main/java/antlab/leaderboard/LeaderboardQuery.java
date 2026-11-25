package antlab.leaderboard;

import antlab.member.MemberKind;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The leaderboard's reads: one CTE text with two tails. {@code fin} is every finished season with its finish key (the time
 * its last turn was written, then the season id, as the season score orders them), whether its league is still open, its
 * parts row of the reference version and its agent config; the mine tail limits it to one member. {@code counted} keeps
 * the seasons that enter L and numbers them per member, newest first. {@code tally} puts every season of a member in
 * exactly one bucket. The board tail lists every member with a season and their newest 10 counted seasons, with the
 * persona name joined through the season's own pin ({@code season_agent_config}, whose key and version reference
 * {@code persona_version}); the mine tail lists one member's finished seasons. Season ids never leave this class. Read
 * only, no lock.
 */
@Component
public class LeaderboardQuery {

    private static final String CTE = """
            WITH fin AS (
                SELECT s.id, s.member_id, t.created_at AS finished_at,
                       COALESCE(l.status = 'OPEN', false) AS league_open,
                       p.season_id IS NOT NULL AS has_parts, p.exclusion,
                       p.part_return, p.part_risk_adjusted, p.part_risk, p.part_process, p.process_score_version,
                       p.final_multiple, c.persona_key, c.persona_version, c.memory_version
                FROM season s
                JOIN turn t ON t.season_id = s.id AND t.turn_no = s.path_length
                LEFT JOIN league_entry le ON le.season_id = s.id
                LEFT JOIN league l ON l.id = le.league_id
                LEFT JOIN season_manager_parts p ON p.season_id = s.id AND p.reference_version = :version
                LEFT JOIN season_agent_config c ON c.season_id = s.id
                WHERE s.status = 'FINISHED' AND (CAST(:member AS bigint) IS NULL OR s.member_id = CAST(:member AS bigint))
            ), counted AS (
                SELECT f.*, row_number() OVER (PARTITION BY f.member_id ORDER BY f.finished_at DESC, f.id DESC) AS rn
                FROM fin f
                WHERE f.has_parts AND f.exclusion IS NULL AND NOT f.league_open
            ), bucketed AS (
                SELECT s.member_id,
                       CASE WHEN s.status = 'VOID' THEN 'VOID'
                            WHEN s.status IN ('IN_PROGRESS', 'PAUSED', 'HALTED', 'ABANDONING') THEN 'UNFINISHED'
                            WHEN COALESCE(l.status = 'OPEN', false) THEN 'AWAITING_LEAGUE_CLOSE'
                            WHEN p.season_id IS NULL THEN 'PENDING'
                            WHEN p.exclusion IS NOT NULL THEN p.exclusion
                            ELSE 'COUNTED' END AS bucket
                FROM season s
                LEFT JOIN league_entry le ON le.season_id = s.id
                LEFT JOIN league l ON l.id = le.league_id
                LEFT JOIN season_manager_parts p ON p.season_id = s.id AND p.reference_version = :version
            ), tally AS (
                SELECT member_id,
                       count(*) FILTER (WHERE bucket = 'COUNTED') AS counted,
                       count(*) FILTER (WHERE bucket = 'VOID') AS voided,
                       count(*) FILTER (WHERE bucket = 'UNFINISHED') AS unfinished,
                       count(*) FILTER (WHERE bucket = 'AWAITING_LEAGUE_CLOSE') AS awaiting_league_close,
                       count(*) FILTER (WHERE bucket = 'PENDING') AS pending,
                       count(*) FILTER (WHERE bucket = 'NOT_STARTED') AS not_started,
                       count(*) FILTER (WHERE bucket = 'REFERENCE_MISMATCH') AS mismatch,
                       count(*) FILTER (WHERE bucket = 'NO_REFERENCE') AS no_reference
                FROM bucketed
                GROUP BY member_id
            )
            """;

    private static final String BOARD = CTE + """
            SELECT m.id AS member_id, m.kind, m.name, ap.owner_member_id, ap.model_id,
                   ta.counted, ta.voided, ta.unfinished, ta.awaiting_league_close, ta.pending, ta.not_started, ta.mismatch,
                   ta.no_reference,
                   k.rn, k.part_return, k.part_risk_adjusted, k.part_risk, k.part_process, k.process_score_version,
                   k.final_multiple, k.persona_version, k.memory_version, pv.display_name AS persona_name
            FROM tally ta
            JOIN member m ON m.id = ta.member_id
            LEFT JOIN agent_profile ap ON ap.member_id = m.id
            LEFT JOIN counted k ON k.member_id = m.id AND k.rn <= :newest
            LEFT JOIN persona_version pv ON pv.persona_key = k.persona_key AND pv.version = k.persona_version
            ORDER BY m.id, k.rn""";

    private static final String MINE = CTE + """
            SELECT row_number() OVER (ORDER BY f.finished_at, f.id) AS season_no, f.league_open, f.has_parts, f.exclusion,
                   f.part_return, f.part_risk_adjusted, f.part_risk, f.part_process, f.process_score_version,
                   f.final_multiple, k.rn
            FROM fin f
            LEFT JOIN counted k ON k.id = f.id
            WHERE f.member_id = :member
            ORDER BY f.finished_at DESC, f.id DESC""";

    /** The four parts, the process score version and the final multiple of one season, as stored. */
    public record Parts(BigDecimal partReturn, BigDecimal partRiskAdjusted, BigDecimal partRisk, BigDecimal partProcess,
            String processScoreVersion, BigDecimal finalMultiple) {
    }

    /** One season L uses: its parts and, for an agent, the persona and memory versions it was pinned to. */
    public record UsedSeason(Parts parts, Integer personaVersion, Integer memoryVersion, String personaName) {
    }

    /**
     * A member with at least one season. {@code used} holds the newest {@link CareerScore#NEWEST} counted seasons, newest
     * first; {@code ownerMemberId} and {@code modelId} are null for a human.
     */
    public record MemberBoard(long memberId, MemberKind kind, String name, Long ownerMemberId, String modelId,
            ManagerLeaderboardDto.Counts counts, List<UsedSeason> used) {
    }

    /** One finished season of the viewer: parts only when it counts ({@code excluded} null). */
    public record MineSeason(int seasonNo, LeaderboardExclusion excluded, boolean used, Parts parts) {
    }

    private final JdbcClient jdbc;

    public LeaderboardQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every member with a season, in member order. */
    public List<MemberBoard> board(String referenceVersion) {
        record Line(long memberId, MemberKind kind, String name, Long owner, String modelId,
                ManagerLeaderboardDto.Counts counts, UsedSeason used) {
        }
        List<Line> lines = jdbc.sql(BOARD).param("version", referenceVersion).param("member", null)
                .param("newest", CareerScore.NEWEST)
                .query((rs, n) -> new Line(rs.getLong("member_id"), MemberKind.valueOf(rs.getString("kind")),
                        rs.getString("name"), rs.getObject("owner_member_id", Long.class), rs.getString("model_id"),
                        counts(rs), rs.getObject("rn") == null ? null
                                : new UsedSeason(parts(rs), rs.getObject("persona_version", Integer.class),
                                        rs.getObject("memory_version", Integer.class), rs.getString("persona_name"))))
                .list();
        Map<Long, MemberBoard> out = new LinkedHashMap<>();
        for (Line line : lines) {
            MemberBoard board = out.computeIfAbsent(line.memberId(), id -> new MemberBoard(id, line.kind(), line.name(),
                    line.owner(), line.modelId(), line.counts(), new ArrayList<>()));
            if (line.used() != null) {
                board.used().add(line.used());
            }
        }
        return out.values().stream().map(b -> new MemberBoard(b.memberId(), b.kind(), b.name(), b.ownerMemberId(),
                b.modelId(), b.counts(), List.copyOf(b.used()))).toList();
    }

    /** The member's finished seasons, newest first, numbered in finish order. */
    public List<MineSeason> mine(String referenceVersion, long memberId) {
        return jdbc.sql(MINE).param("version", referenceVersion).param("member", memberId).query((rs, n) -> {
            LeaderboardExclusion excluded = excluded(rs);
            Long rn = rs.getObject("rn", Long.class);
            return new MineSeason(rs.getInt("season_no"), excluded, rn != null && rn <= CareerScore.NEWEST,
                    excluded == null ? parts(rs) : null);
        }).list();
    }

    private static LeaderboardExclusion excluded(ResultSet rs) throws SQLException {
        if (rs.getBoolean("league_open")) {
            return LeaderboardExclusion.AWAITING_LEAGUE_CLOSE;
        }
        if (!rs.getBoolean("has_parts")) {
            return LeaderboardExclusion.PENDING;
        }
        String exclusion = rs.getString("exclusion");
        return exclusion == null ? null : LeaderboardExclusion.valueOf(ManagerPartsExclusion.valueOf(exclusion).name());
    }

    private static Parts parts(ResultSet rs) throws SQLException {
        return new Parts(rs.getBigDecimal("part_return"), rs.getBigDecimal("part_risk_adjusted"),
                rs.getBigDecimal("part_risk"), rs.getBigDecimal("part_process"), rs.getString("process_score_version"),
                rs.getBigDecimal("final_multiple"));
    }

    private static ManagerLeaderboardDto.Counts counts(ResultSet rs) throws SQLException {
        return new ManagerLeaderboardDto.Counts(rs.getInt("counted"), rs.getInt("voided"), rs.getInt("unfinished"),
                rs.getInt("awaiting_league_close"), rs.getInt("pending"), rs.getInt("not_started"), rs.getInt("mismatch"),
                rs.getInt("no_reference"));
    }
}
