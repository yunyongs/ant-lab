package antlab.feed;

import antlab.common.money.MoneyMath;
import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Assembles the generator's {@link FeedInput} for one turn, the Java counterpart of {@code tf_input} in {@code db/24}. It reads only
 * what the feed of turn t may know: the path rows of turns t and t-1, the events and attention figures of those two blocks, the prices
 * of turns 0..t-1 and the feed stored for turn t-1. The prices of turn t come from the caller (the advance holds them in memory), so
 * nothing here depends on a flush. Six statements, whatever the size of the pool; no block id leaves this class.
 */
@Component
public class FeedInputReader {

    private static final String REVIEWED = """
            SELECT mb.events_reviewed AS reviewed
            FROM season_path sp
            JOIN month_block mb ON mb.id = sp.month_block_id
            WHERE sp.season_id = :seasonId AND sp.turn_no = :turnNo""";

    /** {@code hidden}: a type seen in fewer than three distinct key families anywhere is too rare to name its category. */
    private static final String EVENTS = """
            SELECT e.id AS event_id, e.event_key AS event_key, e.category AS category, e.reaction_change AS reaction,
                   e.strength AS strength,
                   (SELECT count(DISTINCT left(y.event_key, 6)) FROM month_event y WHERE y.event_type = e.event_type) < 3 AS hidden
            FROM season_path sp
            JOIN month_event e ON e.month_block_id = sp.month_block_id
            WHERE sp.season_id = :seasonId AND sp.turn_no = :turnNo
            ORDER BY e.event_key COLLATE "C\"""";

    private static final String ASSETS = """
            SELECT a.id AS asset_id, a.market AS market, a.ticker AS ticker, a.name AS name,
                   g.id AS group_id, g.code AS group_code, g.name AS group_name
            FROM turn_price tp
            JOIN asset a ON a.id = tp.asset_id
            JOIN asset_group g ON g.id = a.asset_group_id
            WHERE tp.season_id = :seasonId AND tp.turn_no = 0
            ORDER BY a.market COLLATE "C", a.ticker COLLATE "C\"""";

    private static final String PRICES = """
            SELECT tp.asset_id AS asset_id, tp.turn_no AS turn_no, tp.price AS price
            FROM turn_price tp
            WHERE tp.season_id = :seasonId AND tp.turn_no < :turnNo
            ORDER BY tp.asset_id, tp.turn_no""";

    private static final String ATTENTION = """
            SELECT sp.turn_no AS turn_no, ama.asset_id AS asset_id, ama.attention_index AS attention
            FROM season_path sp
            JOIN asset_month_attention ama ON ama.month_block_id = sp.month_block_id
            WHERE sp.season_id = :seasonId AND sp.turn_no BETWEEN :previousTurn AND :turnNo""";

    /** Rows of the previous turn's feed that name an asset, in display order (the last story of an asset wins). */
    private static final String PREVIOUS_FEED = """
            SELECT f.asset_id AS asset_id, f.kind AS kind, f.direction AS direction, f.repeat_count AS repeat_count
            FROM turn_feed f
            WHERE f.season_id = :seasonId AND f.turn_no = :previousTurn AND f.asset_id IS NOT NULL
            ORDER BY f.seq""";

    private record AssetRow(long assetId, String market, String ticker, String name, long groupId, String groupCode,
            String groupName) {
    }

    private final JdbcClient jdbc;

    public FeedInputReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param currentPrices turn t's price of every pool asset, as the advance computed them
     * @throws FeedGenerationException {@code FEED_INPUT} (no path row), {@code FEED_EVENTS_UNREVIEWED} or
     *                                 {@code FEED_REACTION_MISSING}
     */
    public FeedInput read(long seasonId, long seed, int turnNo, Map<Long, BigDecimal> currentPrices) {
        List<Boolean> reviewed = jdbc.sql(REVIEWED).param("seasonId", seasonId).param("turnNo", turnNo)
                .query((rs, n) -> rs.getBoolean("reviewed")).list();
        if (reviewed.size() != 1) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "the path of the turn is missing");
        }
        if (!reviewed.get(0)) {
            throw new FeedGenerationException(FeedFailure.FEED_EVENTS_UNREVIEWED, "the events of the turn are not reviewed");
        }
        List<EventFacts> events = jdbc.sql(EVENTS).param("seasonId", seasonId).param("turnNo", turnNo)
                .query((rs, n) -> {
                    boolean hidden = rs.getBoolean("hidden");
                    return new EventFacts(turnNo, rs.getLong("event_id"), rs.getString("event_key"),
                            hidden ? null : rs.getString("category"), rs.getBigDecimal("reaction"),
                            (Integer) rs.getObject("strength"), hidden);
                }).list();
        return new FeedInput(seed, turnNo, readAssets(seasonId, turnNo, currentPrices, true), events);
    }

    /**
     * The asset part of the input, for readers that need no events (the ranking board). Rows of turns above {@code turnNo} are never read.
     *
     * @param withPreviousFeed false skips the previous turn's feed statement; the story fields then stay empty
     */
    public List<AssetFacts> readAssets(long seasonId, int turnNo, Map<Long, BigDecimal> currentPrices, boolean withPreviousFeed) {
        Map<Long, List<BigDecimal>> history = new HashMap<>();
        jdbc.sql(PRICES).param("seasonId", seasonId).param("turnNo", turnNo).query(rs -> {
            history.computeIfAbsent(rs.getLong("asset_id"), k -> new ArrayList<>())
                    .add(MoneyMath.exactStoredPrice(rs.getBigDecimal("price")));
        });
        List<AssetRow> pool = jdbc.sql(ASSETS).param("seasonId", seasonId)
                .query((rs, n) -> new AssetRow(rs.getLong("asset_id"), rs.getString("market"), rs.getString("ticker"),
                        rs.getString("name"), rs.getLong("group_id"), rs.getString("group_code"), rs.getString("group_name")))
                .list();
        for (AssetRow a : pool) {
            List<BigDecimal> seen = history.get(a.assetId());
            if (!currentPrices.containsKey(a.assetId()) || seen == null || seen.size() != turnNo) {
                // A pool asset without a complete price history is damaged data; dropping it would hide the damage.
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "a pool asset has an incomplete price history");
            }
        }

        Map<Long, BigDecimal> attention = new HashMap<>();
        Map<Long, BigDecimal> previousAttention = new HashMap<>();
        jdbc.sql(ATTENTION).param("seasonId", seasonId).param("previousTurn", turnNo - 1).param("turnNo", turnNo)
                .query(rs -> {
                    BigDecimal value = rs.getBigDecimal("attention");
                    if (value != null) {
                        (rs.getInt("turn_no") == turnNo ? attention : previousAttention).put(rs.getLong("asset_id"), value);
                    }
                });

        Map<Long, Story> stories = withPreviousFeed ? previousStories(seasonId, turnNo) : Map.of();
        int ranked = (int) pool.stream().filter(a -> attention.containsKey(a.assetId())).count();
        String focus = pool.stream().filter(a -> attention.containsKey(a.assetId()))
                .min(Comparator.<AssetRow, BigDecimal>comparing(a -> attention.get(a.assetId()), Comparator.reverseOrder())
                        .thenComparing(AssetRow::market, FeedHash::compareBytes)
                        .thenComparing(AssetRow::ticker, FeedHash::compareBytes))
                .map(AssetRow::name).orElse(null);

        List<AssetFacts> facts = new ArrayList<>();
        for (AssetRow a : pool) {
            List<BigDecimal> prices = new ArrayList<>(history.get(a.assetId()));
            prices.add(MoneyMath.exactStoredPrice(currentPrices.get(a.assetId())));
            BigDecimal own = attention.get(a.assetId());
            BigDecimal ownPrevious = previousAttention.get(a.assetId());
            Story story = stories.get(a.assetId());
            facts.add(new AssetFacts(turnNo, a.assetId(), a.market(), a.ticker(), a.name(), a.groupId(), a.groupCode(),
                    a.groupName(), change(prices, turnNo), turnNo >= 2 ? change(prices, turnNo - 1) : null, streak(prices),
                    own, own == null ? null : 1 + (int) pool.stream()
                            .filter(o -> attention.containsKey(o.assetId()) && attention.get(o.assetId()).compareTo(own) > 0).count(),
                    ranked, ownPrevious, ownPrevious == null ? null : 1 + (int) pool.stream()
                            .filter(o -> previousAttention.containsKey(o.assetId())
                                    && previousAttention.get(o.assetId()).compareTo(ownPrevious) > 0).count(),
                    story == null ? null : story.direction(), story == null ? 1 : story.echoRepeat() + 1, focus));
        }
        return facts;
    }

    /** The previous turn's story of one asset: the direction of its last RISE/FALL/ECHO row and its largest ECHO repeat (0 if none). */
    private record Story(MoveDirection direction, int echoRepeat) {
    }

    private Map<Long, Story> previousStories(long seasonId, int turnNo) {
        Map<Long, Story> stories = new HashMap<>();
        if (turnNo < 2) {
            return stories;
        }
        jdbc.sql(PREVIOUS_FEED).param("seasonId", seasonId).param("previousTurn", turnNo - 1).query(rs -> {
            long assetId = rs.getLong("asset_id");
            FeedKind kind = FeedKind.valueOf(rs.getString("kind"));
            String stored = rs.getString("direction");
            Story old = stories.get(assetId);
            MoveDirection direction = old == null ? null : old.direction();
            int repeat = old == null ? 0 : old.echoRepeat();
            if ((kind == FeedKind.RISE || kind == FeedKind.FALL || kind == FeedKind.ECHO) && stored != null) {
                direction = MoveDirection.valueOf(stored);
            }
            if (kind == FeedKind.ECHO) {
                repeat = Math.max(repeat, rs.getInt("repeat_count"));
            }
            stories.put(assetId, new Story(direction, repeat));
        });
        return stories;
    }

    /** {@code round(price[turn] / price[turn - 1] - 1, 8)}; {@code prices} holds turns 0..t, so index = turn. */
    private static BigDecimal change(List<BigDecimal> prices, int turn) {
        return MoneyMath.round8(MoneyMath.pgDivide(prices.get(turn), prices.get(turn - 1)).subtract(BigDecimal.ONE));
    }

    /** Length of the run of equal price-change signs that ends at the last turn (a zero change is a sign of its own). */
    private static int streak(List<BigDecimal> prices) {
        int last = prices.size() - 1;
        int sign = prices.get(last).compareTo(prices.get(last - 1));
        int count = 0;
        for (int turn = last; turn >= 1 && prices.get(turn).compareTo(prices.get(turn - 1)) == sign; turn--) {
            count++;
        }
        return count;
    }
}
