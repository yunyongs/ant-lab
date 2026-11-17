package antlab.scoring;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Two read-only statements of the score service. The first lists a member's finished seasons that have no score row, in
 * finish order (the time the last turn was written, then the season id), so a later season is never rated before an
 * earlier one. The second reads the stored prices of named assets at one turn. No rounding and no write here.
 */
@Component
public class ScoreInputQuery {

    private static final String UNSCORED = """
            SELECT s.id
            FROM season s
            JOIN turn t ON t.season_id = s.id AND t.turn_no = s.path_length
            WHERE s.member_id = :memberId AND s.status = 'FINISHED'
              AND NOT EXISTS (SELECT 1 FROM season_score ss WHERE ss.season_id = s.id)
            ORDER BY t.created_at, s.id""";

    private static final String PRICES = """
            SELECT a.market || ':' || a.ticker AS asset_key, tp.price AS price
            FROM turn_price tp
            JOIN asset a ON a.id = tp.asset_id
            WHERE tp.season_id = :seasonId AND tp.turn_no = :turnNo
              AND (a.market || ':' || a.ticker) = ANY (CAST(:keys AS text[]))""";

    private final JdbcClient jdbc;

    public ScoreInputQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The member's finished seasons without a score row, earliest finish first. */
    public List<Long> unscoredFinished(long memberId) {
        return jdbc.sql(UNSCORED).param("memberId", memberId).query(Long.class).list();
    }

    /** Prices at one turn by asset key ({@code MARKET:TICKER}); a key without a price is missing from the map. */
    public Map<String, BigDecimal> pricesAt(long seasonId, int turnNo, Collection<String> assetKeys) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (assetKeys.isEmpty()) {
            return out;
        }
        jdbc.sql(PRICES).param("seasonId", seasonId).param("turnNo", turnNo).param("keys", assetKeys.toArray(String[]::new))
                .query((rs, n) -> out.put(rs.getString("asset_key"), rs.getBigDecimal("price"))).list();
        return out;
    }
}
