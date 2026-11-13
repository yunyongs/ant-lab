package antlab.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small PromQL name extractor for the dashboard checks. It is regex based, not a parser, and fails closed: an identifier it
 * does not recognise is reported as a violation, so a new PromQL construct needs a change here instead of slipping through.
 */
public final class PromQlNames {

    /** One vector selector. {@code name} is empty for a selector written without a metric name. */
    public record Selector(String name, String matchers) {}

    private static final Pattern SELECTOR = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)?\\s*\\{([^}]*)\\}");
    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern RANGE = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern LABEL_LIST = Pattern.compile("\\b(by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{?\\w+\\}?");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w:.])\\d+(\\.\\d+)?");
    private static final Pattern IDENTIFIER = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)(\\s*\\()?");
    private static final Pattern LABEL_VALUES = Pattern.compile("label_values\\(\\s*([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*,");

    private static final Set<String> FUNCTIONS = Set.of("sum", "max", "min", "avg", "count", "rate", "increase",
            "histogram_quantile", "vector");
    private static final Set<String> KEYWORDS = Set.of("bool", "and", "or", "unless", "offset");

    private PromQlNames() {}

    public static List<Selector> selectors(String expr) {
        List<Selector> found = new ArrayList<>();
        Matcher m = SELECTOR.matcher(expr);
        while (m.find()) {
            found.add(new Selector(m.group(1) == null ? "" : m.group(1), m.group(2)));
        }
        return found;
    }

    /** Metric names selected in the expression, in order of appearance, without duplicates. */
    public static Set<String> names(String expr) {
        Set<String> names = new LinkedHashSet<>();
        for (Selector s : selectors(expr)) {
            if (!s.name().isEmpty()) {
                names.add(s.name());
            }
        }
        return names;
    }

    /**
     * Identifiers outside selectors that are neither a known function nor a keyword (for example {@code rate(jvm_info[5m])}
     * written without braces), plus selectors that have no metric name.
     */
    public static List<String> bareIdentifiers(String expr) {
        List<String> violations = new ArrayList<>();
        for (Selector s : selectors(expr)) {
            if (s.name().isEmpty()) {
                violations.add("selector without a metric name: {" + s.matchers() + "}");
            }
        }
        String rest = SELECTOR.matcher(expr).replaceAll(" ");
        rest = STRING.matcher(rest).replaceAll(" ");
        rest = RANGE.matcher(rest).replaceAll(" ");
        rest = LABEL_LIST.matcher(rest).replaceAll(" ");
        rest = VARIABLE.matcher(rest).replaceAll(" ");
        rest = NUMBER.matcher(rest).replaceAll(" ");
        Matcher m = IDENTIFIER.matcher(rest);
        while (m.find()) {
            String id = m.group(1);
            boolean call = m.group(2) != null;
            if (call ? !FUNCTIONS.contains(id) : !KEYWORDS.contains(id)) {
                violations.add((call ? "unknown function " : "bare metric name ") + id);
            }
        }
        return violations;
    }

    /** Selectors whose matchers lack the application filter. */
    public static List<String> selectorsWithoutApplicationFilter(String expr) {
        List<String> violations = new ArrayList<>();
        for (Selector s : selectors(expr)) {
            if (!s.matchers().contains("application=~\"$application\"")) {
                violations.add(s.name() + "{" + s.matchers() + "}");
            }
        }
        return violations;
    }

    /** Metric name of {@code label_values(<metric>, <label>)}, or empty when the query has another form. */
    public static String variableMetric(String query) {
        Matcher m = LABEL_VALUES.matcher(query);
        return m.find() ? m.group(1) : "";
    }
}
