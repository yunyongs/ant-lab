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
 * document. Texts are compared with every whitespace run collapsed to one space (block scalars fold lines). The
 * comparison is one-sided: a text only the code has is not compared yet (PR C makes it two-sided). Scope:
 * {@code /api/v1/} operations and the component schemas the yaml declares.
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

    private static String squash(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    /** One line per difference: {@code pointer — yaml: … / code: …}. Empty when the texts match. */
    public static List<String> diff(JsonNode yamlDoc, JsonNode codeDoc) {
        Map<String, String> yaml = texts(yamlDoc, false, yamlDoc);
        Map<String, String> code = texts(codeDoc, true, yamlDoc);
        List<String> out = new ArrayList<>();
        for (String pointer : new TreeSet<>(yaml.keySet())) {
            String y = squash(yaml.get(pointer));
            String c = code.containsKey(pointer) ? squash(code.get(pointer)) : null;
            if (!y.equals(c)) {
                out.add(pointer + " — yaml: " + y + " / code: " + (c == null ? "(absent)" : c));
            }
        }
        return out;
    }
}
