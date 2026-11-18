package antlab.scoring;

import antlab.asset.Position;
import antlab.scoring.DefenderGoalCalculator.HeldTurn;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Source of the defender goals: one read-only native statement whatever the number of orders, assets or turns. For every turn t of
 * 1..T and every asset held at its start (cumulative fills of turns before t, rebuilt from the fill history as the report does) it
 * returns the asset's price at t-1 and t and the lineup interval that was open as of turn t-1, {@code start_turn <= t-1 < end_turn}
 * (an open end counts as infinite): a position changed during turn t-1 already applies to the move into t, one changed during turn t
 * does not. A missing price or a number of matching intervals other than one is a contradiction; the rows are refused here. No
 * rounding and no write.
 */
@Component
public class DefenderHoldingQuery {

    private static final String HELD = """
            WITH po AS (
                SELECT o.asset_id, o.turn_no, sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE -e.quantity END) AS dq
                FROM orders o
                JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :seasonId AND o.turn_no < :reportTurn
                GROUP BY o.asset_id, o.turn_no
            ), held AS (
                SELECT x.asset_id, g.n AS turn_no, sum(po.dq) AS quantity
                FROM (SELECT DISTINCT po0.asset_id FROM po po0) x
                CROSS JOIN generate_series(1, CAST(:reportTurn AS integer)) AS g(n)
                JOIN po ON po.asset_id = x.asset_id AND po.turn_no < g.n
                GROUP BY x.asset_id, g.n
                HAVING sum(po.dq) <> 0
            )
            SELECT h.asset_id AS asset_id, h.turn_no AS turn_no, h.quantity AS quantity, p0.price AS previous_price,
                   p1.price AS price, lu.declared_position AS declared_position
            FROM held h
            LEFT JOIN turn_price p0 ON p0.season_id = :seasonId AND p0.asset_id = h.asset_id AND p0.turn_no = h.turn_no - 1
            LEFT JOIN turn_price p1 ON p1.season_id = :seasonId AND p1.asset_id = h.asset_id AND p1.turn_no = h.turn_no
            LEFT JOIN lineup lu ON lu.season_id = :seasonId AND lu.asset_id = h.asset_id
                               AND lu.start_turn <= h.turn_no - 1 AND (lu.end_turn IS NULL OR h.turn_no - 1 < lu.end_turn)
            ORDER BY h.turn_no, h.asset_id, lu.id""";

    private final JdbcClient jdbc;

    public DefenderHoldingQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Holdings at the start of turns 1..T with prices and declared position, in turn then asset order. */
    public List<HeldTurn> held(long seasonId, int reportTurn) {
        List<Row> rows = jdbc.sql(HELD).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .query((rs, n) -> new Row(rs.getLong("asset_id"), rs.getInt("turn_no"), rs.getLong("quantity"),
                        rs.getBigDecimal("previous_price"), rs.getBigDecimal("price"), rs.getString("declared_position")))
                .list();
        List<HeldTurn> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean sameAsNext = i + 1 < rows.size() && rows.get(i + 1).sameHolding(row);
            boolean sameAsPrevious = i > 0 && rows.get(i - 1).sameHolding(row);
            if (sameAsNext || sameAsPrevious) {
                throw new IllegalStateException("수비수 득점 원천이 모순이다: 보유한 턴에 선언 구간이 둘 이상이다");
            }
            if (row.declared == null) {
                throw new IllegalStateException("수비수 득점 원천이 모순이다: 보유한 턴에 선언 구간이 없다");
            }
            if (row.previousPrice == null || row.price == null) {
                throw new IllegalStateException("수비수 득점 원천이 모순이다: 보유 종목의 턴 가격이 없다");
            }
            out.add(new HeldTurn(row.turnNo, row.assetId, row.quantity, row.previousPrice, row.price,
                    Position.valueOf(row.declared)));
        }
        return out;
    }

    private record Row(long assetId, int turnNo, long quantity, java.math.BigDecimal previousPrice, java.math.BigDecimal price,
            String declared) {
        boolean sameHolding(Row other) {
            return assetId == other.assetId && turnNo == other.turnNo;
        }
    }
}
