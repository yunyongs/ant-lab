package antlab.agent.memory;

import antlab.agent.decision.SchemaViolation;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shape check of the two memory documents, by hand, against {@code docs/api/season-reflection-1.schema.json} and
 * {@code docs/api/operating-note-1.schema.json} (a parity test holds the two to the same verdicts). It runs before the
 * leak filter, which skips what is not a string and would let {@code {"x": "2008년"}} through as a "good" line. Lengths are
 * code points after NFC, the text that is stored. A violation is a pointer and a keyword; it never carries a value or a key
 * the model chose (an unknown key is reported at its parent). Strings that PostgreSQL cannot store (NUL, a lone surrogate)
 * are violations of the keyword {@code encoding}.
 */
public final class MemoryDocuments {

    public static final String REFLECTION_SCHEMA = "season-reflection/1";
    public static final String NOTE_SCHEMA = "operating-note/1";
    static final int REFLECTION_LINE_MAX = 120;
    static final int NOTE_RULE_MAX = 150;
    private static final int MAX_VIOLATIONS = 20;
    private static final Set<String> MISTAKE_TYPES = Set.of("RISK_BREACH", "PANIC_SELL", "CHASE_BUY", "OVERTRADE",
            "CONCENTRATION", "STRUCTURE_DRIFT", "ROLE_MISS", "NOISE_FOLLOW", "NUDGE_FOLLOW", "OTHER");
    private static final Set<String> REFLECTION_KEYS = Set.of("schema", "good", "mistakes", "ruleCandidates");
    private static final Set<String> MISTAKE_KEYS = Set.of("type", "text");
    private static final Set<String> NOTE_KEYS = Set.of("schema", "rules");

    private final List<SchemaViolation> violations = new ArrayList<>();

    private MemoryDocuments() {
    }

    public static List<SchemaViolation> checkReflection(JsonNode doc) {
        MemoryDocuments check = new MemoryDocuments();
        check.reflection(doc);
        return List.copyOf(check.violations);
    }

    public static List<SchemaViolation> checkNote(JsonNode doc) {
        MemoryDocuments check = new MemoryDocuments();
        check.note(doc);
        return List.copyOf(check.violations);
    }

    /** A copy with every string value in NFC; apply it to a document that passed the shape check, and store that copy. */
    public static JsonNode nfc(JsonNode doc) {
        if (doc.isString()) {
            return JsonMapper.shared().valueToTree(MemoryLeakFilter.nfc(doc.asString()));
        }
        if (doc.isArray()) {
            ArrayNode out = JsonMapper.shared().createArrayNode();
            doc.forEach(item -> out.add(nfc(item)));
            return out;
        }
        if (doc.isObject()) {
            ObjectNode out = JsonMapper.shared().createObjectNode();
            doc.properties().forEach(e -> out.set(e.getKey(), nfc(e.getValue())));
            return out;
        }
        return doc;
    }

    private void violate(String pointer, String keyword) {
        if (violations.size() < MAX_VIOLATIONS) {
            violations.add(new SchemaViolation(pointer, keyword));
        }
    }

    private void reflection(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            violate("", "type");
            return;
        }
        unknownKeys(doc, REFLECTION_KEYS, "");
        for (String key : new String[] {"schema", "good", "mistakes", "ruleCandidates"}) {
            if (!doc.has(key)) {
                violate("/" + key, "required");
            }
        }
        constant(doc.get("schema"), REFLECTION_SCHEMA, "/schema");
        lines(doc.get("good"), "/good", 3, 3, REFLECTION_LINE_MAX);
        JsonNode mistakes = doc.get("mistakes");
        if (mistakes != null) {
            if (!mistakes.isArray()) {
                violate("/mistakes", "type");
            } else {
                if (mistakes.size() > 3) {
                    violate("/mistakes", "maxItems");
                }
                for (int i = 0; i < mistakes.size(); i++) {
                    mistake(mistakes.get(i), "/mistakes/" + i);
                }
            }
        }
        lines(doc.get("ruleCandidates"), "/ruleCandidates", 0, 3, REFLECTION_LINE_MAX);
    }

    private void mistake(JsonNode item, String pointer) {
        if (!item.isObject()) {
            violate(pointer, "type");
            return;
        }
        unknownKeys(item, MISTAKE_KEYS, pointer);
        for (String key : new String[] {"type", "text"}) {
            if (!item.has(key)) {
                violate(pointer + "/" + key, "required");
            }
        }
        JsonNode type = item.get("type");
        if (type != null && !(type.isString() && MISTAKE_TYPES.contains(type.asString()))) {
            violate(pointer + "/type", "enum");
        }
        JsonNode text = item.get("text");
        if (text != null) {
            line(text, pointer + "/text", REFLECTION_LINE_MAX);
        }
    }

    private void note(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            violate("", "type");
            return;
        }
        unknownKeys(doc, NOTE_KEYS, "");
        for (String key : new String[] {"schema", "rules"}) {
            if (!doc.has(key)) {
                violate("/" + key, "required");
            }
        }
        constant(doc.get("schema"), NOTE_SCHEMA, "/schema");
        lines(doc.get("rules"), "/rules", 1, 15, NOTE_RULE_MAX);
    }

    private void unknownKeys(JsonNode object, Set<String> known, String pointer) {
        for (String name : object.propertyNames()) {
            if (!known.contains(name)) {
                violate(pointer, "additionalProperties");
                return;
            }
        }
    }

    private void constant(JsonNode node, String expected, String pointer) {
        if (node != null && !(node.isString() && expected.equals(node.asString()))) {
            violate(pointer, "const");
        }
    }

    /** An array of strings of 1 to {@code max} characters, with {@code minItems} to {@code maxItems} items. */
    private void lines(JsonNode array, String pointer, int minItems, int maxItems, int max) {
        if (array == null) {
            return;
        }
        if (!array.isArray()) {
            violate(pointer, "type");
            return;
        }
        if (array.size() < minItems) {
            violate(pointer, "minItems");
        }
        if (array.size() > maxItems) {
            violate(pointer, "maxItems");
        }
        for (int i = 0; i < array.size(); i++) {
            line(array.get(i), pointer + "/" + i, max);
        }
    }

    private void line(JsonNode node, String pointer, int max) {
        if (!node.isString()) {
            violate(pointer, "type");
            return;
        }
        String text = node.asString();
        if (!storable(text)) {
            violate(pointer, "encoding");
            return;
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        int length = nfc.codePointCount(0, nfc.length());
        if (length < 1) {
            violate(pointer, "minLength");
        } else if (length > max) {
            violate(pointer, "maxLength");
        }
    }

    /** False for a NUL or an unpaired surrogate, which the database refuses. */
    static boolean storable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000') {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
