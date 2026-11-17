package antlab.feed;

/** One row of {@code feed/notices.csv}: a sentence the screen shows as it is, not rendered per turn. */
public record FeedNotice(String id, boolean active, String text) {
}
