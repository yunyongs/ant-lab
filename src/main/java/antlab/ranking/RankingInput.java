package antlab.ranking;

import antlab.feed.AssetFacts;
import antlab.feed.FeedFailure;
import antlab.feed.FeedGenerationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only input of the ranking board: a turn number and asset facts that are all as of that turn. There is no field of a block, month,
 * date or path type, so a later turn cannot be passed in by construction; the constructor also refuses a fact tagged with another turn.
 */
public record RankingInput(int turnNo, List<AssetFacts> assets) {

    public RankingInput {
        if (turnNo < 1 || assets == null) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "turn or fact list is missing");
        }
        for (AssetFacts a : assets) {
            if (a == null) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "the fact list holds an empty entry");
            }
        }
        assets = List.copyOf(assets);
        Set<String> keys = new HashSet<>();
        for (AssetFacts a : assets) {
            if (a.asOfTurn() != turnNo) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "an asset fact is not as of the board's turn");
            }
            if (!keys.add(a.market() + ":" + a.ticker())) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "duplicate asset");
            }
            boolean ranked = a.attentionRank() != null;
            if (ranked != (a.attention() != null) || (ranked && (a.attentionRank() < 1 || a.attentionRank() > a.attentionN()))) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "attention figure and rank do not agree");
            }
        }
    }
}
