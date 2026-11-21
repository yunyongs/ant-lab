package antlab.season;

/**
 * One feed item an order cited, with the order's turn and the turn the item was shown in (an order may cite an earlier turn's item): display number and stored kind only. Read by the reflection
 * view of a finished agent season; carries no row, event or block id and no text.
 */
public record CitedFeedRow(int turnNo, int feedTurnNo, int seq, FeedKind kind) {
}
