package antlab.support;

import antlab.api.error.ErrorHttpMapping;
import antlab.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code x-error-codes} layer of the contract: per non-2xx response, and {@code x-framework-errors} for the
 * statuses the framework raises on every operation (405, 406, 415). A {@code $ref} response is resolved first.
 */
public final class ContractErrorCodes {

    /** Answered only by the local dev endpoint, which is outside the contract. */
    public static final Set<ErrorCode> UNLISTED_ALLOWED = EnumSet.of(ErrorCode.FORBIDDEN);

    private static final String RESPONSE_REF_PREFIX = "#/components/responses/";

    private ContractErrorCodes() {
    }

    /** {@code "METHOD /path" -> status -> listed codes} for every non-2xx response; a missing list is an empty list. */
    public static Map<String, Map<Integer, List<String>>> perOperation(JsonNode doc) {
        Map<String, Map<Integer, List<String>>> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!op.getValue().has("responses")) {
                    continue;
                }
                Map<Integer, List<String>> byStatus = new TreeMap<>();
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    int status = Integer.parseInt(response.getKey());
                    if (status / 100 != 2) {
                        byStatus.put(status, codes(resolve(doc, response.getValue())));
                    }
                }
                out.put(op.getKey().toUpperCase() + " " + path.getKey(), byStatus);
            }
        }
        return out;
    }

    /** {@code status -> codes} from the top-level {@code x-framework-errors}. */
    public static Map<Integer, List<String>> framework(JsonNode doc) {
        Map<Integer, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : doc.path("x-framework-errors").properties()) {
            out.put(Integer.parseInt(e.getKey()), codes(e.getValue()));
        }
        return out;
    }

    /** True when the contract lists {@code code} for this answer: under the operation's status, or as a framework error. */
    public static boolean isListed(JsonNode doc, String operationKey, int status, String code) {
        List<String> perOp = perOperation(doc).getOrDefault(operationKey, Map.of()).getOrDefault(status, List.of());
        return perOp.contains(code) || framework(doc).getOrDefault(status, List.of()).contains(code);
    }

    /** Every problem in the error layer; empty when the contract is consistent with {@link ErrorHttpMapping}. */
    public static List<String> violations(JsonNode doc) {
        List<String> out = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    int status = Integer.parseInt(response.getKey());
                    if (status / 100 == 2) {
                        continue;
                    }
                    String where = op.getKey().toUpperCase() + " " + path.getKey() + " " + status;
                    JsonNode resolved = resolve(doc, response.getValue());
                    checkResponse(where, status, resolved, resolved.path("x-error-codes"), listed, out);
                }
            }
        }
        for (Map.Entry<String, JsonNode> e : doc.path("x-framework-errors").properties()) {
            int status = Integer.parseInt(e.getKey());
            String where = "x-framework-errors " + status;
            if (shapeOk(where, e.getValue(), out)) {
                List<String> codes = codes(e.getValue());
                check(where, status, codes, out);
                listed.addAll(codes);
            }
        }
        for (ErrorCode code : ErrorCode.values()) {
            if (!UNLISTED_ALLOWED.contains(code) && !listed.contains(code.name())) {
                out.add("ErrorCode " + code + " is not listed in any x-error-codes or x-framework-errors");
            }
        }
        return out;
    }

    private static void checkResponse(String where, int status, JsonNode response, JsonNode list, Set<String> listed,
            List<String> out) {
        if (list.isMissingNode()) {
            check(where, status, List.of(), out);
            return;
        }
        if (!shapeOk(where, list, out)) {
            return;
        }
        List<String> codes = codes(response);
        check(where, status, codes, out);
        listed.addAll(codes);
        String description = response.path("description").asString("");
        if (!description.isBlank() && !codes.isEmpty()) {
            List<String> named = namedCodes(description);
            if (!named.equals(codes)) {
                out.add(where + ": x-error-codes " + codes + " differ from the codes the description names " + named);
            }
        }
    }

    /** {@code x-error-codes} must be an array of strings without repeats. */
    private static boolean shapeOk(String where, JsonNode list, List<String> out) {
        if (!list.isArray() || !StreamSupport.stream(list.spliterator(), false).allMatch(JsonNode::isString)) {
            out.add(where + ": x-error-codes must be an array of strings");
            return false;
        }
        List<String> names = codes(list);
        if (new HashSet<>(names).size() != names.size()) {
            out.add(where + ": x-error-codes lists a code twice");
        }
        return true;
    }

    /** ErrorCode names in the text, word-bounded, distinct, in order of first appearance. */
    private static List<String> namedCodes(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b[A-Z][A-Z_]*[A-Z]\\b").matcher(text);
        while (m.find()) {
            try {
                names.add(ErrorCode.valueOf(m.group()).name());
            } catch (IllegalArgumentException e) {
                // not an ErrorCode, ordinary capitalised word
            }
        }
        return new ArrayList<>(names);
    }

    private static void check(String where, int status, List<String> codes, List<String> out) {
        if (codes.isEmpty()) {
            out.add(where + ": x-error-codes is missing or empty");
        }
        for (String name : codes) {
            ErrorCode code;
            try {
                code = ErrorCode.valueOf(name);
            } catch (IllegalArgumentException e) {
                out.add(where + ": " + name + " is not an ErrorCode");
                continue;
            }
            int mapped = ErrorHttpMapping.status(code).value();
            if (mapped != status) {
                out.add(where + ": " + name + " maps to " + mapped + ", not " + status);
            }
        }
    }

    private static JsonNode resolve(JsonNode doc, JsonNode response) {
        String ref = response.path("$ref").asString("");
        if (ref.startsWith(RESPONSE_REF_PREFIX)) {
            return doc.path("components").path("responses").path(ref.substring(RESPONSE_REF_PREFIX.length()));
        }
        return response;
    }

    private static List<String> codes(JsonNode holder) {
        List<String> out = new ArrayList<>();
        JsonNode array = holder.path("x-error-codes");
        if (holder.isArray()) {
            array = holder;
        }
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
