package antlab.scoring;

import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Source of the baseline simulation: one read-only native statement that returns the stored {@code turn_price} series of turns 0..T
 * for the representative candidates (market index pair, bond candidates, gold, growth). An asset that is in the season pool has a
 * turn-0 price, so the keys of the result are the pool members among the candidates. No rounding and no write here; the number of
 * statements does not depend on the candidates, the holdings or the turns.
 */
@Component
public class SeasonAnalysisQuery {

    private static final String PRICES = """
            SELECT a.market AS market, a.ticker AS ticker, tp.turn_no AS turn_no, tp.price AS price
            FROM turn_price tp
            JOIN asset a ON a.id = tp.asset_id
            WHERE tp.season_id = :seasonId AND tp.turn_no BETWEEN 0 AND :reportTurn
              AND (a.market || ':' || a.ticker) = ANY (CAST(:keys AS text[]))
            ORDER BY a.market, a.ticker, tp.turn_no""";

    private final JdbcClient jdbc;

    public SeasonAnalysisQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Turn 0..T prices of the candidate assets that the season holds, in (market, ticker, turn) order. */
    public Map<AssetKey, List<BigDecimal>> prices(long seasonId, int reportTurn, List<AssetKey> candidates) {
        String[] keys = candidates.stream().map(c -> c.market() + ":" + c.ticker()).toArray(String[]::new);
        Map<AssetKey, List<BigDecimal>> out = new LinkedHashMap<>();
        jdbc.sql(PRICES).param("seasonId", seasonId).param("reportTurn", reportTurn).param("keys", keys)
                .query((rs, n) -> {
                    out.computeIfAbsent(new AssetKey(rs.getString("market"), rs.getString("ticker")), k -> new ArrayList<>())
                            .add(rs.getBigDecimal("price"));
                    return null;
                }).list();
        return out;
    }
}
