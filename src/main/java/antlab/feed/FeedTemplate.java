package antlab.feed;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One row of {@code feed/templates.csv}. */
public record FeedTemplate(String id, FeedKind kind, boolean active, String target, MoveDirection direction, Integer strength,
        String eventCategory, String eventType, String outcome, boolean categoryHidden, String text) {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]*)\\}");

    /** The distinct placeholder names of a sentence, in order of first appearance. */
    static List<String> placeholdersOf(String text) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return new ArrayList<>(names);
    }

    public List<String> placeholders() {
        return placeholdersOf(text);
    }
}
