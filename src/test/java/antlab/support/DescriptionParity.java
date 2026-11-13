package antlab.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Operation summary/description, response descriptions ({@code $ref} responses are skipped; the shared
 * {@code InternalError} is compared on its own) and schema/property descriptions of the contract against the generated
 * document, in both directions: a text only one side has is reported as {@code (absent)}. Scope: {@code /api/v1/}
 * operations and the component schemas the yaml declares.
 *
 * <p>Paragraph rule (bilingual strings): a text is a list of paragraphs separated by a blank line. Inside a paragraph
 * every whitespace run is one space and a line break folds to a space, except that a line starting with {@code "- "}
 * starts a list item on its own line. So re-wrapping a block scalar or a split Java literal never matters, but moving
 * a paragraph or list-item boundary does. A short text is one line {@code English / 한국어}; a long one is an English
 * paragraph, a blank line, then the Korean paragraph.
 */
public final class DescriptionParity {

    private DescriptionParity() {
    }

    /** {@code JSON pointer -> text} of every compared string in one document. */
    public static Map<String, String> texts(JsonNode doc, boolean onlyYamlSchemas, JsonNode yamlDoc) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/v1/")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                for (String key : List.of("summary", "description")) {
                    if (op.getValue().path(key).isString()) {
                        out.put("/paths/" + path.getKey() + "/" + op.getKey() + "/" + key,
                                op.getValue().get(key).asString());
                    }
                }
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    if (!response.getValue().has("$ref") && response.getValue().path("description").isString()) {
                        out.put("/paths/" + path.getKey() + "/" + op.getKey() + "/responses/" + response.getKey()
                                + "/description", response.getValue().get("description").asString());
                    }
                }
            }
        }
        JsonNode internalError = doc.path("components").path("responses").path("InternalError").path("description");
        if (internalError.isString()) {
            out.put("/components/responses/InternalError/description", internalError.asString());
        }
        for (Map.Entry<String, JsonNode> schema : doc.path("components").path("schemas").properties()) {
            if (onlyYamlSchemas && !yamlDoc.path("components").path("schemas").has(schema.getKey())) {
                continue;
            }
            collectDescriptions("/components/schemas/" + schema.getKey(), schema.getValue(), out);
        }
        return out;
    }

    private static void collectDescriptions(String pointer, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (e.getKey().equals("description") && e.getValue().isString()) {
                    out.put(pointer + "/description", e.getValue().asString());
                } else {
                    collectDescriptions(pointer + "/" + e.getKey(), e.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectDescriptions(pointer + "/" + i, node.get(i), out);
            }
        }
    }

    /** Paragraphs split at blank lines; inside one, lines fold to a space unless they start a {@code "- "} item. */
    static String normalize(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.strip().split("\\n[ \\t]*\\n")) {
            List<String> lines = new ArrayList<>();
            for (String raw : paragraph.strip().split("\\n")) {
                String line = raw.strip().replaceAll("\\s+", " ");
                if (lines.isEmpty() || line.startsWith("- ")) {
                    lines.add(line);
                } else {
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + line);
                }
            }
            paragraphs.add(String.join("\n", lines));
        }
        return String.join("\n\n", paragraphs);
    }

    private static final java.util.regex.Pattern HANGUL = java.util.regex.Pattern.compile("[\\uAC00-\\uD7A3]");
    /** A plain English word: letters only, lower case after the first, so {@code turnNo} and {@code NOT_FOUND} do not count. */
    private static final java.util.regex.Pattern ENGLISH_WORD = java.util.regex.Pattern.compile("\\b[A-Za-z][a-z]{2,}\\b");

    /**
     * One line per text that is not English first and Korean after: the part before the first Hangul syllable must
     * hold an English word, and the text must hold Korean.
     */
    public static List<String> languageIssues(Map<String, String> texts) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(texts).entrySet()) {
            java.util.regex.Matcher hangul = HANGUL.matcher(e.getValue());
            if (!hangul.find()) {
                out.add(e.getKey() + " — no Korean");
            } else if (!ENGLISH_WORD.matcher(e.getValue().substring(0, hangul.start())).find()) {
                out.add(e.getKey() + " — no English before the Korean");
            }
        }
        return out;
    }

    /** One line per difference: {@code pointer — yaml: … / code: …}. Empty when the texts match. */
    public static List<String> diff(JsonNode yamlDoc, JsonNode codeDoc) {
        Map<String, String> yaml = texts(yamlDoc, false, yamlDoc);
        Map<String, String> code = texts(codeDoc, true, yamlDoc);
        List<String> out = new ArrayList<>();
        TreeSet<String> pointers = new TreeSet<>(yaml.keySet());
        pointers.addAll(code.keySet());
        for (String pointer : pointers) {
            String y = yaml.containsKey(pointer) ? normalize(yaml.get(pointer)) : null;
            String c = code.containsKey(pointer) ? normalize(code.get(pointer)) : null;
            if (!java.util.Objects.equals(y, c)) {
                out.add(pointer + " — yaml: " + (y == null ? "(absent)" : y) + " / code: " + (c == null ? "(absent)" : c));
            }
        }
        return out;
    }
}
