package antlab.season;

import antlab.history.EventCategory;
import antlab.history.EventPhase;

/**
 * One stored feed item of a finished season as read by the review feed query of {@code TurnFeedRepository}: the item as shown,
 * its stored kind, the event tag it was built from (all event fields null unless the kind is EVENT) and how many orders
 * reference it. Carries no row, event or block id.
 */
public record RevealedFeedRow(int turnNo, int seq, FeedKind kind, Long assetId, Long groupId, String body,
        EventCategory category, String eventType, EventPhase phase, String title, String hindsight, String source,
        Long ordersFromItem) {
}
