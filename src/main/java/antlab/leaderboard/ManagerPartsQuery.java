package antlab.leaderboard;

import java.util.Collection;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The parts queue: finished seasons without a row for a reference version, earliest finish first (the time the last turn
 * was written, then the season id, as the season score orders them). Seasons of a league that is still open are in the
 * queue too; the leaderboard hides them until the league closes. The next season to work on leaves out an unscored season
 * whose member has an earlier finished season without a score: it can only be scored after that one, which is either
 * ahead of it in the queue or waiting. Read-only, no lock.
 */
@Component
public class ManagerPartsQuery {

    /** A season waiting for its parts row and its owner. */
    record Pending(long seasonId, long memberId) {
    }

    private static final String QUEUE = """
            FROM season s
            JOIN turn t ON t.season_id = s.id AND t.turn_no = s.path_length
            WHERE s.status = 'FINISHED'
              AND NOT EXISTS (SELECT 1 FROM season_manager_parts p
                              WHERE p.season_id = s.id AND p.reference_version = :version)
            """;

    private static final String NEXT = "SELECT s.id, s.member_id " + QUEUE + """
              AND s.id <> ALL (CAST(:skip AS bigint[]))
              AND s.member_id <> ALL (CAST(:members AS bigint[]))
              AND (EXISTS (SELECT 1 FROM season_score ss WHERE ss.season_id = s.id)
                   OR NOT EXISTS (SELECT 1
                                  FROM season e
                                  JOIN turn et ON et.season_id = e.id AND et.turn_no = e.path_length
                                  WHERE e.member_id = s.member_id AND e.status = 'FINISHED'
                                    AND (et.created_at, e.id) < (t.created_at, s.id)
                                    AND NOT EXISTS (SELECT 1 FROM season_score es WHERE es.season_id = e.id)))
            ORDER BY t.created_at, s.id
            LIMIT 1""";

    private static final String COUNT = "SELECT count(*) " + QUEUE;

    private final JdbcClient jdbc;

    public ManagerPartsQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The earliest finished season without a row, leaving out the named seasons and every season of the named members. */
    Optional<Pending> next(String referenceVersion, Collection<Long> skip, Collection<Long> skipMembers) {
        return jdbc.sql(NEXT)
                .param("version", referenceVersion)
                .param("skip", skip.toArray(Long[]::new))
                .param("members", skipMembers.toArray(Long[]::new))
                .query((rs, n) -> new Pending(rs.getLong("id"), rs.getLong("member_id")))
                .optional();
    }

    /** How many finished seasons have no row for the reference version. */
    int pendingCount(String referenceVersion) {
        return jdbc.sql(COUNT).param("version", referenceVersion).query(Integer.class).single();
    }
}
