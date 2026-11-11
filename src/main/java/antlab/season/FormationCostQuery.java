package antlab.season;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 비용 내부 조회 — SQL {@code formation_costs} 와 같은 문장이다. 체결을 <b>주문별로 먼저 합친 뒤</b> 변경별로 합친다
 * (다중 체결 주문이 주문 수·비용을 중복해 세지 않게). 주문 없는 변경도 한 줄(주문 0건)로 포함한다.
 * 체결 비용(execution)만 더한다 — 원장의 FEE 는 대사의 독립 대조용이라 여기서 더하지 않는다.
 * 대금은 체결가 × 수량의 정확한 합이며 다시 반올림하지 않는다. 계산 결과를 저장하지 않는다(erd D31).
 */
@Component
public class FormationCostQuery {

    private static final String SQL = """
            WITH po AS (
                SELECT o.id, o.formation_change_id AS fcid, o.side,
                       sum(e.fill_price * e.quantity) AS notional, sum(e.fee) AS fee, sum(e.tax) AS tax
                FROM orders o
                JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :seasonId AND o.formation_change_id IS NOT NULL
                GROUP BY o.id, o.formation_change_id, o.side
            )
            SELECT fc.id AS id, fc.turn_no AS turn_no,
                   count(po.id) AS order_count,
                   COALESCE(sum(po.notional) FILTER (WHERE po.side = 'BUY'), 0) AS buy_notional,
                   COALESCE(sum(po.notional) FILTER (WHERE po.side = 'SELL'), 0) AS sell_notional,
                   COALESCE(sum(po.fee), 0) AS fee_sum,
                   COALESCE(sum(po.tax), 0) AS tax_sum
            FROM formation_change fc
            LEFT JOIN po ON po.fcid = fc.id
            WHERE fc.season_id = :seasonId
            GROUP BY fc.id, fc.turn_no
            ORDER BY fc.id""";

    private final JdbcClient jdbc;

    public FormationCostQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 시즌의 변경별 비용, 변경 번호 순. */
    @Transactional(readOnly = true)
    public List<FormationCost> bySeason(long seasonId) {
        return jdbc.sql(SQL).param("seasonId", seasonId).query((rs, n) -> new FormationCost(rs.getLong("id"),
                rs.getInt("turn_no"), rs.getLong("order_count"), rs.getBigDecimal("buy_notional"),
                rs.getBigDecimal("sell_notional"), rs.getBigDecimal("fee_sum"), rs.getBigDecimal("tax_sum"))).list();
    }
}
