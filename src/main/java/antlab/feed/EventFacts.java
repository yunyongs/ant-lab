package antlab.feed;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One reviewed event tag of the current block. {@code category} is null when the tag is hidden (a rare type), so the generator only
 * knows the hidden flag. A tag without a reaction cannot become a card: that is {@link FeedFailure#FEED_REACTION_MISSING}.
 */
public record EventFacts(int asOfTurn, long monthEventId, String eventKey, String category, BigDecimal reaction, Integer strength,
        boolean hidden) {

    public EventFacts {
        Objects.requireNonNull(eventKey, "eventKey");
        if (reaction == null) {
            throw new FeedGenerationException(FeedFailure.FEED_REACTION_MISSING, "an event tag has no reaction");
        }
        if (hidden && category != null) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "a hidden event carries its category");
        }
        if (!hidden && category == null) {
            throw new FeedGenerationException(FeedFailure.FEED_INPUT, "a public event has no category");
        }
    }
}
