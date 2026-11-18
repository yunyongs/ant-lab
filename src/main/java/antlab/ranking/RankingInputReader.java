package antlab.ranking;

import antlab.feed.FeedInputReader;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RankingInput} of one played turn from stored rows. It reads the prices of the turn itself and, through the feed
 * reader, those of the turns before it plus the attention of the turn's block and the previous one; nothing of a later turn and no event.
 * Four statements, whatever the size of the pool.
 */
@Component
public class RankingInputReader {

    private static final String TURN_PRICES = """
            SELECT tp.asset_id AS asset_id, tp.price AS price
            FROM turn_price tp
            WHERE tp.season_id = :seasonId AND tp.turn_no = :turnNo""";

    private final JdbcClient jdbc;
    private final FeedInputReader assets;

    public RankingInputReader(JdbcClient jdbc, FeedInputReader assets) {
        this.jdbc = jdbc;
        this.assets = assets;
    }

    public RankingInput read(long seasonId, int turnNo) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        jdbc.sql(TURN_PRICES).param("seasonId", seasonId).param("turnNo", turnNo)
                .query(rs -> {
                    prices.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
                });
        return new RankingInput(turnNo, assets.readAssets(seasonId, turnNo, prices, false));
    }
}
