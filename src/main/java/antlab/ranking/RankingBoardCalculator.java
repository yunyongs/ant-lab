package antlab.ranking;

import antlab.common.money.MoneyMath;
import antlab.feed.AssetFacts;
import antlab.feed.FeedHash;
import antlab.feed.FeedRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Ranking board of one turn as a pure function of {@link RankingInput}: no clock, no randomness, no stored state. Every list is
 * filtered, sorted by a total order (ties end in market, ticker, then group code, all by UTF-8 byte order), cut at
 * {@link RankingRules#BOARD_SIZE} and numbered by row position.
 */
public final class RankingBoardCalculator {

    private static final Comparator<AssetFacts> NATURAL = Comparator.comparing(AssetFacts::market, FeedHash::compareBytes)
            .thenComparing(AssetFacts::ticker, FeedHash::compareBytes);

    private record GroupChange(long id, String code, BigDecimal change) {
    }

    private RankingBoardCalculator() {
    }

    public static RankingBoard board(RankingInput input) {
        List<AssetFacts> assets = input.assets();
        Comparator<AssetFacts> byRetDesc = Comparator.comparing(AssetFacts::ret, Comparator.reverseOrder()).thenComparing(NATURAL);
        Comparator<AssetFacts> byRetAsc = Comparator.comparing(AssetFacts::ret).thenComparing(NATURAL);
        Comparator<AssetFacts> byStreak = Comparator.comparingInt(AssetFacts::streak).reversed()
                .thenComparing(Comparator.comparing((AssetFacts a) -> a.ret().abs(), Comparator.reverseOrder())).thenComparing(NATURAL);
        Comparator<AssetFacts> byAttention = Comparator.comparing(AssetFacts::attention, Comparator.reverseOrder()).thenComparing(NATURAL);

        List<RankingBoard.Asset> gainers = assetRows(top(assets, a -> a.ret().signum() > 0, byRetDesc));
        List<RankingBoard.Asset> losers = assetRows(top(assets, a -> a.ret().signum() < 0, byRetAsc));
        List<RankingBoard.Attention> attention = attentionRows(top(assets, a -> a.attention() != null, byAttention));
        List<RankingBoard.Streak> rising = streakRows(
                top(assets, a -> a.ret().signum() > 0 && a.streak() >= RankingRules.MIN_STREAK, byStreak));
        List<RankingBoard.Streak> falling = streakRows(
                top(assets, a -> a.ret().signum() < 0 && a.streak() >= RankingRules.MIN_STREAK, byStreak));

        List<GroupChange> groups = groups(assets);
        Comparator<GroupChange> byChangeDesc = Comparator.comparing(GroupChange::change, Comparator.reverseOrder())
                .thenComparing(GroupChange::code, FeedHash::compareBytes);
        Comparator<GroupChange> byChangeAsc = Comparator.comparing(GroupChange::change)
                .thenComparing(GroupChange::code, FeedHash::compareBytes);
        return new RankingBoard(input.turnNo(), gainers, losers, attention, rising, falling,
                groupRows(top(groups, g -> g.change().signum() > 0, byChangeDesc)),
                groupRows(top(groups, g -> g.change().signum() < 0, byChangeAsc)));
    }

    private static <T> List<T> top(List<T> rows, Predicate<T> keep, Comparator<T> order) {
        return rows.stream().filter(keep).sorted(order).limit(RankingRules.BOARD_SIZE).toList();
    }

    private static List<RankingBoard.Asset> assetRows(List<AssetFacts> sorted) {
        List<RankingBoard.Asset> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            out.add(new RankingBoard.Asset(i + 1, sorted.get(i).assetId(), sorted.get(i).ret()));
        }
        return out;
    }

    private static List<RankingBoard.Attention> attentionRows(List<AssetFacts> sorted) {
        List<RankingBoard.Attention> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            AssetFacts a = sorted.get(i);
            out.add(new RankingBoard.Attention(i + 1, a.assetId(), FeedRules.grade(a.attentionRank(), a.attentionN())));
        }
        return out;
    }

    private static List<RankingBoard.Streak> streakRows(List<AssetFacts> sorted) {
        List<RankingBoard.Streak> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            AssetFacts a = sorted.get(i);
            out.add(new RankingBoard.Streak(i + 1, a.assetId(), a.streak(), a.ret()));
        }
        return out;
    }

    private static List<RankingBoard.Group> groupRows(List<GroupChange> sorted) {
        List<RankingBoard.Group> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            out.add(new RankingBoard.Group(i + 1, sorted.get(i).id(), sorted.get(i).change()));
        }
        return out;
    }

    /** Group change as the feed states it: the mean of member returns, rounded once to 8 digits. */
    private static List<GroupChange> groups(List<AssetFacts> assets) {
        Map<Long, List<AssetFacts>> byGroup = new LinkedHashMap<>();
        for (AssetFacts a : assets) {
            byGroup.computeIfAbsent(a.groupId(), k -> new ArrayList<>()).add(a);
        }
        List<GroupChange> out = new ArrayList<>();
        for (Map.Entry<Long, List<AssetFacts>> e : byGroup.entrySet()) {
            List<BigDecimal> rets = e.getValue().stream().map(AssetFacts::ret).toList();
            out.add(new GroupChange(e.getKey(), e.getValue().get(0).groupCode(), MoneyMath.round8(MoneyMath.pgAvg(rets))));
        }
        return out;
    }
}
