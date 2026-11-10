package antlab.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAPI 문서를 "계약으로 비교할 것"만 남긴 평범한 자바 구조(맵·목록·문자열·불리언)로 바꾼다.
 * 저장소 계약(yaml)과 springdoc 출력(JSON)을 같은 규칙으로 줄여 {@link OpenApiDiff} 가 비교한다.
 *
 * <p>비교하는 것: 범위 안(/api/v1/) 운영의 operationId·deprecated·parameters·requestBody·responses, 스키마의
 * type·format·enum·required·properties·items·additionalProperties·수치/길이 제약·oneOf/anyOf/allOf,
 * 범위 안 운영에서 닿는 컴포넌트 집합, 머리의 openapi 주·부 판. 무시하는 것: summary·description·tags·
 * example(s)·default·readOnly/writeOnly·title·xml·info·servers·security.
 * $ref 는 컴포넌트 이름으로 바꿔 비교한다(이름도 계약 — 웹 타입 이름).
 */
public final class OpenApiNormalizer {

    static final String SCOPE_PREFIX = "/api/v1/";
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    /** 값이 그대로 비교되는 스칼라 제약 키 */
    private static final List<String> SCALAR_KEYS = List.of("format", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
            "multipleOf", "const", "minProperties", "maxProperties");

    private final JsonNode doc;
    private final Set<String> reached = new TreeSet<>();
    private final Deque<String> pending = new ArrayDeque<>();

    private OpenApiNormalizer(JsonNode doc) {
        this.doc = doc;
    }

    /** yaml 문자열을 JSON 트리로 읽는다. 따옴표 없는 {@code 200:} 같은 정수 키는 문자열 키가 된다. */
    public static JsonNode fromYaml(String yaml) {
        Object loaded = new Yaml().load(yaml);
        return JsonMapper.shared().valueToTree(loaded);
    }

    public static JsonNode fromJson(String json) {
        return JsonMapper.shared().readTree(json);
    }

    public static Map<String, Object> normalize(JsonNode doc) {
        return new OpenApiNormalizer(doc).run();
    }

    private Map<String, Object> run() {
        Map<String, Object> out = new TreeMap<>();
        String version = doc.path("openapi").asString("");
        String[] parts = version.split("\\.");
        out.put("openapi", parts.length >= 2 ? parts[0] + "." + parts[1] : version);

        Map<String, Object> operations = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            if (!path.getKey().startsWith(SCOPE_PREFIX)) {
                continue;
            }
            for (String method : METHODS) {
                JsonNode op = path.getValue().get(method);
                if (op != null) {
                    operations.put(method.toUpperCase() + " " + path.getKey(), operation(op));
                }
            }
        }
        out.put("operations", operations);

        JsonNode schemas = doc.path("components").path("schemas");
        Map<String, Object> components = new TreeMap<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            JsonNode schema = schemas.get(name);
            components.put(name, schema == null ? Map.of("missing", Boolean.TRUE) : schema(schema));
        }
        out.put("components", components);
        List<String> orphans = new ArrayList<>();
        for (String name : schemas.propertyNames()) {
            if (!reached.contains(name)) {
                orphans.add(name);
            }
        }
        orphans.sort(null);
        out.put("orphans", orphans);
        return out;
    }

    private Map<String, Object> operation(JsonNode op) {
        Map<String, Object> m = new TreeMap<>();
        m.put("operationId", op.path("operationId").asString(""));
        m.put("deprecated", op.path("deprecated").asBoolean(false));

        Map<String, Object> parameters = new TreeMap<>();
        for (JsonNode p : op.path("parameters")) {
            if (p.has("$ref")) {
                parameters.put("$ref " + p.get("$ref").asString(), Map.of());
                continue;
            }
            Map<String, Object> pm = new TreeMap<>();
            pm.put("required", p.path("required").asBoolean(false));
            pm.put("schema", schema(p.path("schema")));
            parameters.put(p.path("name").asString("") + " in " + p.path("in").asString(""), pm);
        }
        m.put("parameters", parameters);

        JsonNode body = op.get("requestBody");
        if (body != null) {
            Map<String, Object> bm = new TreeMap<>();
            bm.put("required", body.path("required").asBoolean(false));
            bm.put("content", content(body.path("content")));
            m.put("requestBody", bm);
        }

        Map<String, Object> responses = new TreeMap<>();
        for (Map.Entry<String, JsonNode> r : op.path("responses").properties()) {
            Map<String, Object> rm = new TreeMap<>();
            rm.put("content", content(r.getValue().path("content")));
            responses.put(r.getKey(), rm);
        }
        m.put("responses", responses);
        return m;
    }

    private Map<String, Object> content(JsonNode content) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : content.properties()) {
            m.put(e.getKey().toLowerCase(), schema(e.getValue().path("schema")));
        }
        return m;
    }

    /** 스키마 한 개를 줄인다. {@code $ref} 가 있으면 이름만 남기고 대상 컴포넌트를 닿은 목록에 올린다. */
    private Object schema(JsonNode s) {
        if (s == null || s.isMissingNode() || s.isNull()) {
            return Map.of();
        }
        if (s.isBoolean()) {
            return s.booleanValue();
        }
        JsonNode ref = s.get("$ref");
        if (ref != null) {
            String target = ref.asString();
            if (target.startsWith(SCHEMA_REF_PREFIX)) {
                String name = target.substring(SCHEMA_REF_PREFIX.length());
                if (reached.add(name)) {
                    pending.add(name);
                }
                return Map.of("$ref", name);
            }
            return Map.of("$ref", target);
        }
        Map<String, Object> m = new TreeMap<>();
        JsonNode type = s.get("type");
        if (type != null) {
            m.put("type", typeSet(type));
        }
        for (String key : SCALAR_KEYS) {
            JsonNode v = s.get(key);
            if (v != null) {
                m.put(key, scalar(v));
            }
        }
        JsonNode en = s.get("enum");
        if (en != null) {
            m.put("enum", sortedScalars(en));
        }
        JsonNode required = s.get("required");
        if (required != null && required.isArray()) {
            m.put("required", sortedScalars(required));
        }
        JsonNode props = s.get("properties");
        if (props != null) {
            Map<String, Object> pm = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : props.properties()) {
                pm.put(e.getKey(), schema(e.getValue()));
            }
            m.put("properties", pm);
        }
        if (s.has("items")) {
            m.put("items", schema(s.get("items")));
        }
        if (s.has("additionalProperties")) {
            m.put("additionalProperties", schema(s.get("additionalProperties")));
        }
        for (String key : List.of("oneOf", "anyOf", "allOf")) {
            JsonNode arr = s.get(key);
            if (arr != null && arr.isArray()) {
                List<Object> items = new ArrayList<>();
                arr.forEach(x -> items.add(schema(x)));
                items.sort((a, b) -> a.toString().compareTo(b.toString()));
                m.put(key, items);
            }
        }
        return m;
    }

    /** type 은 문자열 또는 목록 — 항상 정렬한 집합으로 본다(null 토큰은 "null" 문자열). */
    private static List<String> typeSet(JsonNode type) {
        List<String> out = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(t -> out.add(t.isNull() ? "null" : t.asString()));
        } else {
            out.add(type.isNull() ? "null" : type.asString());
        }
        out.sort(null);
        return out;
    }

    private static List<String> sortedScalars(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(scalar(x)));
        out.sort(null);
        return out;
    }

    private static String scalar(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (v.isNull()) {
            return "null";
        }
        return v.isString() ? v.asString() : v.toString();
    }
}
