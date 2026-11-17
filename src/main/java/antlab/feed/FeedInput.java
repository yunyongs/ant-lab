package antlab.feed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only input of the generator: the season seed, the turn, and facts that are all as of that turn. There is no field of a block,
 * month, date or path type, so a later turn cannot be passed in by construction; the constructor also refuses a fact tagged with another
 * turn.
 */
public record FeedInput(long seed, int turnNo, List<AssetFacts> assets, List<EventFacts> events) {

    public FeedInput {
        if (turnNo < 1 || assets == null || events == null) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "turn or fact list is missing");
        }
        assets = List.copyOf(assets);
        events = List.copyOf(events);
        Set<String> keys = new HashSet<>();
        for (AssetFacts a : assets) {
            if (a.asOfTurn() != turnNo) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "an asset fact is not as of the current turn");
            }
            if (!keys.add(a.market() + ":" + a.ticker())) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "duplicate asset");
            }
        }
        keys.clear();
        for (EventFacts e : events) {
            if (e.asOfTurn() != turnNo) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "an event fact is not as of the current turn");
            }
            if (!keys.add(e.eventKey())) {
                throw new FeedGenerationException(FeedFailure.FEED_INPUT, "duplicate event");
            }
        }
    }
}
