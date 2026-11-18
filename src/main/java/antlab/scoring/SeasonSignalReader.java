package antlab.scoring;

import antlab.history.MoveDirection;
import antlab.ranking.RankingInputReader;
import antlab.scoring.TurnSignals.FeedRow;
import antlab.season.FeedKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Signals of every judged turn 1..T-1 of a finished season (MVP-19). One statement reads the stored feed rows of those turns and each
 * turn's board input comes from {@link RankingInputReader} (four statements per turn), the same call the ranking endpoint makes, so
 * "what was on the board" has one definition. Each turn's signals see only that turn's rows. Read-only.
 */
@Component
public class SeasonSignalReader {

    private static final String FEED = """
            SELECT f.turn_no AS turn_no, f.kind AS kind, f.asset_id AS asset_id, f.direction AS direction
            FROM turn_feed f
            WHERE f.season_id = :seasonId AND f.turn_no BETWEEN 1 AND :lastTurn
            ORDER BY f.turn_no, f.seq""";

    private final JdbcClient jdbc;
    private final RankingInputReader boards;

    public SeasonSignalReader(JdbcClient jdbc, RankingInputReader boards) {
        this.jdbc = jdbc;
        this.boards = boards;
    }

    /** Signals of turns 1..T-1 in turn order (empty when T = 1). */
    public List<TurnSignals> read(long seasonId, int reportTurn) {
        int last = reportTurn - 1;
        if (last < 1) {
            return List.of();
        }
        Map<Integer, List<FeedRow>> rows = new HashMap<>();
        jdbc.sql(FEED).param("seasonId", seasonId).param("lastTurn", last).query(rs -> {
            int turn = rs.getInt("turn_no");
            String direction = rs.getString("direction");
            rows.computeIfAbsent(turn, k -> new ArrayList<>()).add(new FeedRow(turn, FeedKind.valueOf(rs.getString("kind")),
                    rs.getObject("asset_id", Long.class), direction == null ? null : MoveDirection.valueOf(direction)));
        });
        List<TurnSignals> out = new ArrayList<>();
        for (int turn = 1; turn <= last; turn++) {
            out.add(TurnSignals.of(turn, rows.getOrDefault(turn, List.of()), boards.read(seasonId, turn)));
        }
        return out;
    }
}
