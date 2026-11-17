package antlab.season;

/**
 * What the order source rule needs to know about one stored feed item: its internal id (never sent out), the asset chip it
 * shows (null when it names none) and whether it is an alert. The alert flag is computed in the query, so the item's
 * stored kind is not readable from the entity.
 */
public record FeedSourceRow(Long id, Long assetId, boolean alert) {
}
