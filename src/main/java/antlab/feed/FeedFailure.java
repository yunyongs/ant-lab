package antlab.feed;

/** Why a feed could not be produced. Names equal the HINT codes of {@code db/24_turn_feed_def.sql}. */
public enum FeedFailure {
    FEED_INPUT,
    FEED_EVENTS_UNREVIEWED,
    FEED_REACTION_MISSING,
    FEED_RENDER
}
