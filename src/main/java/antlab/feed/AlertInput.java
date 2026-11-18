package antlab.feed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only input of the alert generator: the season seed, the turn, the asset facts of that turn and the player's facts. There is no
 * field of a block, month, date or path type, so a later turn cannot be passed in by construction; the constructor also refuses a fact
 * tagged with another turn.
 */
public record AlertInput(long seed, int turnNo, List<AssetFacts> assets, PlayerFacts player) {

    public AlertInput {
        if (turnNo < 1 || assets == null || player == null) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "turn, fact list or player facts are missing");
        }
        for (AssetFacts a : assets) {
            if (a == null) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "the fact list holds an empty entry");
            }
        }
        assets = List.copyOf(assets);
        if (player.asOfTurn() != turnNo) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "the player facts are not as of the current turn");
        }
        Set<String> keys = new HashSet<>();
        for (AssetFacts a : assets) {
            if (a.asOfTurn() != turnNo) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "an asset fact is not as of the current turn");
            }
            if (!keys.add(a.market() + ":" + a.ticker())) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "duplicate asset");
            }
        }
    }
}
