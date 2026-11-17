package antlab.feed;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;

/**
 * One generated {@code turn_feed} row without keys and timestamps. {@code templateVarsJson} is the canonical text PostgreSQL gives a
 * {@code jsonb} (keys by length then bytes, {@code ": "} and {@code ", "} separators), so it compares equal to the SQL generator's text.
 */
public record GeneratedFeedRow(int seq, FeedKind kind, Long assetId, Long assetGroupId, Long monthEventId, boolean categoryHidden,
        MoveDirection direction, Integer strength, int repeatCount, String templateId, String templateVarsJson, String body) {
}
