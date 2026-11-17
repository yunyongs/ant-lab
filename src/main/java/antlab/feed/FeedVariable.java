package antlab.feed;

import antlab.season.FeedKind;
import java.util.Set;

/** One row of {@code feed/variables.csv}: a slot name, how its value renders, and which kinds may use it. */
public record FeedVariable(String name, String type, String source, int turnOffset, Set<FeedKind> kinds, Integer maxLen) {

    public static final String TYPE_NAME = "name";
    public static final String TYPE_PCT_SIGNED = "pct_signed";
    public static final String TYPE_PCT_UNSIGNED = "pct_unsigned";
    public static final String TYPE_INT = "int";
}
