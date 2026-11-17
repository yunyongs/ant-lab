package antlab.feed;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The templates, variables, notices and deny list of {@code feed/}, loaded once. Any rule failure throws, so the application does not
 * start with a catalog the generator could trip over later. The SQL reference is {@code feed/check_templates.sql}; this class checks the
 * structural subset the generator needs, and {@code FeedTextSafetyTest} repeats the sentence rules as a second line of defence.
 */
@Component
public final class FeedTemplateCatalog {

    static final List<String> KINDS_HEADER = List.of("kind", "name_ko", "screen", "target");
    static final List<String> VARIABLES_HEADER = List.of("name", "type", "source", "turn_offset", "kinds", "max_len");
    static final List<String> TEMPLATES_HEADER = List.of("template_id", "kind", "status", "target", "direction", "strength",
            "event_category", "event_type", "outcome", "category_hidden", "text");
    static final List<String> NOTICES_HEADER = List.of("notice_id", "status", "text");
    static final List<String> DENYLIST_HEADER = List.of("word", "reason", "note");

    private static final Pattern TEMPLATE_ID = Pattern
            .compile("(rise|fall|echo|spread|fade|event|nudge)\\.[a-z][a-z0-9_]*\\.v[1-9][0-9]*");
    private static final Pattern NOTICE_ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.v[1-9][0-9]*");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final Pattern VERSION_SUFFIX = Pattern.compile("\\.v[1-9][0-9]*$");
    private static final Set<String> TARGETS = Set.of("ASSET", "GROUP", "NONE");
    private static final Set<String> CATEGORIES = Set.of("GEOPOLITICS", "TRADE", "POLITICS", "SUPPLY_SHOCK", "FINANCIAL", "POLICY",
            "HEALTH");
    private static final Set<String> VARIABLE_TYPES = Set.of(FeedVariable.TYPE_NAME, FeedVariable.TYPE_PCT_SIGNED,
            FeedVariable.TYPE_PCT_UNSIGNED, FeedVariable.TYPE_INT);
    private static final int MAX_TEXT = 300;

    private final Map<FeedKind, Set<String>> kindTargets;
    private final Map<String, FeedVariable> variables;
    private final List<FeedTemplate> templates;
    private final Map<String, FeedTemplate> activeById;
    private final Map<String, List<FeedTemplate>> activeByKindTarget;
    private final Set<String> eventShapes;
    private final List<FeedNotice> notices;
    private final List<String> denyWords;

    /** Spring entry point: the copy of {@code feed/} on the classpath. */
    public FeedTemplateCatalog() {
        this(FeedFiles.classpath(FeedTemplateCatalog.class.getClassLoader()));
    }

    FeedTemplateCatalog(FeedFiles files) {
        try {
            this.kindTargets = loadKinds(read(files, "kinds.csv", KINDS_HEADER));
            this.variables = loadVariables(read(files, "variables.csv", VARIABLES_HEADER));
            this.templates = loadTemplates(read(files, "templates.csv", TEMPLATES_HEADER));
            this.denyWords = loadDenyWords(read(files, "denylist.csv", DENYLIST_HEADER));
            this.notices = loadNotices(read(files, "notices.csv", NOTICES_HEADER));
        } catch (IOException e) {
            throw new IllegalStateException("feed files cannot be read: " + e.getMessage(), e);
        }
        this.activeById = new LinkedHashMap<>();
        this.activeByKindTarget = new LinkedHashMap<>();
        List<FeedTemplate> active = new ArrayList<>(templates.stream().filter(FeedTemplate::active).toList());
        active.sort((a, b) -> FeedHash.compareBytes(a.id(), b.id()));
        for (FeedTemplate t : active) {
            activeById.put(t.id(), t);
            activeByKindTarget.computeIfAbsent(key(t.kind(), t.target()), k -> new ArrayList<>()).add(t);
        }
        Set<String> shapes = new HashSet<>();
        for (FeedTemplate t : active) {
            if (t.kind() == FeedKind.EVENT) {
                shapes.add(shape(t.text()));
            }
        }
        this.eventShapes = Collections.unmodifiableSet(shapes);
    }

    static FeedTemplateCatalog fromDirectory(java.nio.file.Path dir) {
        return new FeedTemplateCatalog(FeedFiles.directory(dir));
    }

    private static List<List<String>> read(FeedFiles files, String name, List<String> header) throws IOException {
        try {
            return FeedCsvReader.read(FeedFiles.decode(files.read(name)), header);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("feed/" + name + ": " + e.getMessage(), e);
        }
    }

    // ---- queries ----

    /**
     * Templates a candidate may use, in template-id byte order (SQL {@code tf_template_pool}): ACTIVE, same kind and target; for an EVENT
     * the hidden flag and the category must match and type and outcome must be blank, otherwise the direction must be equal (null equals
     * null); every placeholder must be a key with a value; and when the rendered whole percent {@code r} is 0 only sentences whose shape
     * equals the shape of an ACTIVE event card remain.
     */
    List<FeedTemplate> pool(FeedKind kind, String target, MoveDirection direction, Integer r, Set<String> keys, boolean hidden,
            String category) {
        List<FeedTemplate> out = new ArrayList<>();
        for (FeedTemplate t : activeByKindTarget.getOrDefault(key(kind, target), List.of())) {
            boolean attributes;
            if (kind == FeedKind.EVENT) {
                attributes = t.categoryHidden() == hidden && (hidden || (t.eventCategory() != null && t.eventCategory().equals(category)))
                        && t.eventType() == null && t.outcome() == null;
            } else {
                attributes = t.direction() == direction;
            }
            if (attributes && keys.containsAll(t.placeholders()) && (r == null || r != 0 || eventShapes.contains(shape(t.text())))) {
                out.add(t);
            }
        }
        return out;
    }

    /** The sentence with each slot replaced by its variable's type (SQL {@code tf_shape}); an unknown slot becomes {@code ?}. */
    String shape(String text) {
        Matcher m = FeedTemplate.PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            FeedVariable v = variables.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement("{" + (v == null ? "?" : v.type()) + "}"));
        }
        m.appendTail(out);
        return out.toString();
    }

    Optional<FeedTemplate> active(String templateId) {
        return Optional.ofNullable(activeById.get(templateId));
    }

    public List<FeedTemplate> templates() {
        return templates;
    }

    public Map<String, FeedVariable> variables() {
        return variables;
    }

    public List<FeedNotice> notices() {
        return notices;
    }

    public List<String> denyWords() {
        return denyWords;
    }

    public Map<FeedKind, Set<String>> kindTargets() {
        return kindTargets;
    }

    private static String key(FeedKind kind, String target) {
        return kind.name() + "/" + target;
    }

    // ---- loading ----

    private static Map<FeedKind, Set<String>> loadKinds(List<List<String>> rows) {
        Map<FeedKind, Set<String>> out = new EnumMap<>(FeedKind.class);
        for (List<String> row : rows) {
            FeedKind kind = kindOf(row.get(0), "KIND");
            if (out.containsKey(kind)) {
                throw fail("kinds.csv", "KIND duplicate " + kind);
            }
            if (!Set.of("FEED", "ALERT").contains(row.get(2))) {
                throw fail("kinds.csv", "KIND screen of " + kind);
            }
            Set<String> targets = new TreeSet<>(Arrays.asList(required(row.get(3), "kinds.csv", "target").split(";")));
            if (!TARGETS.containsAll(targets)) {
                throw fail("kinds.csv", "KIND target of " + kind);
            }
            out.put(kind, targets);
        }
        if (!out.keySet().equals(Set.of(FeedKind.values()))) {
            throw fail("kinds.csv", "KINDS_ERD codes differ from FeedKind");
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, FeedVariable> loadVariables(List<List<String>> rows) {
        Map<String, FeedVariable> out = new LinkedHashMap<>();
        for (List<String> row : rows) {
            String name = required(row.get(0), "variables.csv", "name");
            if (!VARIABLE_NAME.matcher(name).matches() || out.containsKey(name)) {
                throw fail("variables.csv", "VAR_UNKNOWN name " + name);
            }
            String type = required(row.get(1), "variables.csv", "type");
            if (!VARIABLE_TYPES.contains(type)) {
                throw fail("variables.csv", "VAR_TYPE of " + name);
            }
            int offset = integer(row.get(3), "variables.csv", "turn_offset");
            if (offset > 0) {
                throw fail("variables.csv", "VAR_FUTURE offset of " + name);
            }
            Set<FeedKind> kinds = new TreeSet<>();
            for (String kind : required(row.get(4), "variables.csv", "kinds").split(";")) {
                kinds.add(kindOf(kind, "VAR_KIND"));
            }
            Integer maxLen = row.get(5) == null ? null : integer(row.get(5), "variables.csv", "max_len");
            out.put(name, new FeedVariable(name, type, row.get(2), offset, Set.copyOf(kinds), maxLen));
        }
        return Collections.unmodifiableMap(out);
    }

    private List<FeedTemplate> loadTemplates(List<List<String>> rows) {
        List<FeedTemplate> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> activeSlugs = new HashSet<>();
        for (List<String> row : rows) {
            String id = required(row.get(0), "templates.csv", "template_id");
            if (id.length() > 40 || !TEMPLATE_ID.matcher(id).matches()) {
                throw fail("templates.csv", "ID_FORMAT " + id);
            }
            if (!ids.add(id)) {
                throw fail("templates.csv", "ID_DUP " + id);
            }
            FeedKind kind = kindOf(row.get(1), "KIND");
            if (!id.startsWith(kind.name().toLowerCase() + ".")) {
                throw fail("templates.csv", "ID_KIND " + id);
            }
            boolean active = status(row.get(2), "templates.csv");
            String target = required(row.get(3), "templates.csv", "target");
            if (!kindTargets.get(kind).contains(target)) {
                throw fail("templates.csv", "TARGET_KIND " + id);
            }
            MoveDirection direction = row.get(4) == null ? null : directionOf(row.get(4), id);
            Integer strength = row.get(5) == null ? null : integer(row.get(5), "templates.csv", "strength");
            if (strength != null && (strength < 1 || strength > 3)) {
                throw fail("templates.csv", "strength of " + id);
            }
            String category = row.get(6);
            if (category != null && !CATEGORIES.contains(category)) {
                throw fail("templates.csv", "EVENT_ATTR " + id);
            }
            boolean hidden = switch (required(row.get(9), "templates.csv", "category_hidden")) {
                case "true" -> true;
                case "false" -> false;
                default -> throw fail("templates.csv", "category_hidden of " + id);
            };
            String text = required(row.get(10), "templates.csv", "text");
            if (text.codePointCount(0, text.length()) > MAX_TEXT) {
                throw fail("templates.csv", "LENGTH " + id);
            }
            if (kind != FeedKind.NUDGE && active && FeedTemplate.placeholdersOf(text).isEmpty()) {
                throw fail("templates.csv", "SLOT_FREE " + id);
            }
            for (String slot : FeedTemplate.placeholdersOf(text)) {
                FeedVariable v = variables.get(slot);
                if (v == null) {
                    throw fail("templates.csv", "VAR_UNKNOWN " + slot + " in " + id);
                }
                if (!v.kinds().contains(kind)) {
                    throw fail("templates.csv", "VAR_KIND " + slot + " in " + id);
                }
            }
            if (active && !activeSlugs.add(VERSION_SUFFIX.matcher(id).replaceFirst(""))) {
                throw fail("templates.csv", "ID_ACTIVE " + id);
            }
            out.add(new FeedTemplate(id, kind, active, target, direction, strength, category, row.get(7), row.get(8), hidden, text));
        }
        return List.copyOf(out);
    }

    private static List<String> loadDenyWords(List<List<String>> rows) {
        List<String> out = new ArrayList<>();
        for (List<String> row : rows) {
            out.add(required(row.get(0), "denylist.csv", "word"));
        }
        return List.copyOf(out);
    }

    private List<FeedNotice> loadNotices(List<List<String>> rows) {
        List<FeedNotice> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> activeBases = new HashSet<>();
        for (List<String> row : rows) {
            String id = required(row.get(0), "notices.csv", "notice_id");
            if (!NOTICE_ID.matcher(id).matches()) {
                throw fail("notices.csv", "ID_FORMAT " + id);
            }
            if (!ids.add(id)) {
                throw fail("notices.csv", "ID_DUP " + id);
            }
            boolean active = status(row.get(1), "notices.csv");
            String text = required(row.get(2), "notices.csv", "text");
            if (active && !activeBases.add(VERSION_SUFFIX.matcher(id).replaceFirst(""))) {
                throw fail("notices.csv", "ID_ACTIVE " + id);
            }
            String problem = noticeProblem(text);
            if (problem != null) {
                throw fail("notices.csv", problem + " in " + id);
            }
            out.add(new FeedNotice(id, active, text));
        }
        return List.copyOf(out);
    }

    /** A fixed sentence has no slot, no digit, no Latin letter, no percent sign and no deny-list word; null when it is clean. */
    private String noticeProblem(String text) {
        if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0) {
            return "BRACE";
        }
        if (text.indexOf('%') >= 0) {
            return "PERCENT";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || (Character.isLetter(c) && c < 0x3000 && !(c >= 0xAC00 && c <= 0xD7A3))
                    || (c >= 0xFF21 && c <= 0xFF5A)) {
                return "CHARSET";
            }
        }
        for (String word : denyWords) {
            if (text.contains(word)) {
                return "DENY";
            }
        }
        return null;
    }

    private static FeedKind kindOf(String value, String rule) {
        try {
            return FeedKind.valueOf(required(value, "feed", "kind"));
        } catch (IllegalArgumentException e) {
            throw fail("feed", rule + " unknown kind");
        }
    }

    private static MoveDirection directionOf(String value, String id) {
        try {
            return MoveDirection.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw fail("templates.csv", "DIRECTION of " + id);
        }
    }

    private static boolean status(String value, String file) {
        return switch (required(value, file, "status")) {
            case "ACTIVE" -> true;
            case "RETIRED" -> false;
            default -> throw fail(file, "status");
        };
    }

    private static String required(String value, String file, String column) {
        if (value == null || value.isBlank()) {
            throw fail(file, "empty " + column);
        }
        return value;
    }

    private static int integer(String value, String file, String column) {
        try {
            return Integer.parseInt(required(value, file, column));
        } catch (NumberFormatException e) {
            throw fail(file, "not an integer: " + column);
        }
    }

    private static IllegalStateException fail(String file, String reason) {
        return new IllegalStateException("feed/" + file + ": " + reason);
    }
}
