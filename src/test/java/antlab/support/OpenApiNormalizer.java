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
 * <p>비교하는 것: 범위 안(/api/v1/) 운영의 operationId·deprecated·parameters(style·explode 는 어느 한쪽이라도 적었을
 * 때만 — 기본값은 계약에 적지 않는다)·requestBody·responses(본문 스키마와 응답 헤더의 이름 집합·필수 여부·스키마), 스키마의
 * type·format·enum·required·properties·items·additionalProperties·수치/길이 제약·oneOf/anyOf/allOf,
 * 범위 안 운영에서 닿는 컴포넌트 집합, 머리의 openapi 주·부 판. 미디어 형식은 {@code ;} 앞(예: charset 매개변수
 * 제외)까지만 본다. 응답의 {@code #/components/responses/…} 참조는 풀어서 본문을 비교한다. 범위 밖 운영만 쓰는 컴포넌트는 고아로 세지 않는다.
 * 무시하는 것: summary·description·tags·example(s)·default·readOnly/writeOnly·title·xml·info·servers·security.
 * $ref 는 컴포넌트 이름으로 바꿔 비교한다(이름도 계약 — 웹 타입 이름).
 */
public final class OpenApiNormalizer {

    static final String SCOPE_PREFIX = "/api/v1/";
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final String RESPONSE_REF_PREFIX = "#/components/responses/";
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
        Set<String> outside = outOfScopeComponents();
        for (String name : schemas.propertyNames()) {
            if (!reached.contains(name) && !outside.contains(name)) {
                orphans.add(name);
            }
        }
        orphans.sort(null);
        out.put("orphans", orphans);
        return out;
    }

    /** 범위 밖 경로가 직접·간접으로 쓰는 스키마 이름. 이 컴포넌트는 범위 안 운영이 안 써도 고아가 아니다. */
    private Set<String> outOfScopeComponents() {
        Set<String> found = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            if (!path.getKey().startsWith(SCOPE_PREFIX)) {
                collectRefs(path.getValue(), found, queue);
            }
        }
        JsonNode schemas = doc.path("components").path("schemas");
        while (!queue.isEmpty()) {
            JsonNode target = schemas.get(queue.poll());
            if (target != null) {
                collectRefs(target, found, queue);
            }
        }
        return found;
    }

    private static void collectRefs(JsonNode node, Set<String> found, Deque<String> queue) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString() && ref.asString().startsWith(SCHEMA_REF_PREFIX)) {
                String name = ref.asString().substring(SCHEMA_REF_PREFIX.length());
                if (found.add(name)) {
                    queue.add(name);
                }
            }
            node.forEach(child -> collectRefs(child, found, queue));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, found, queue));
        }
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
            // style·explode 는 기본값이 위치마다 달라 "생략"과 "기본값 명시"를 같게 보지 않는다.
            // 한쪽만 적어도 차이이므로 기본값(query 의 explode: true 등)은 yaml 에 적지 않는다.
            for (String key : List.of("style", "explode")) {
                if (p.has(key)) {
                    pm.put(key, scalar(p.get(key)));
                }
            }
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
            JsonNode response = resolveResponse(r.getValue());
            rm.put("content", content(response.path("content")));
            rm.put("headers", headers(response.path("headers")));
            responses.put(r.getKey(), rm);
        }
        m.put("responses", responses);
        return m;
    }

    /** 응답이 {@code #/components/responses/이름} 참조면 그 컴포넌트를 돌려준다(없으면 빈 노드 — 차이로 드러난다). */
    private JsonNode resolveResponse(JsonNode response) {
        JsonNode ref = response.get("$ref");
        if (ref == null || !ref.asString().startsWith(RESPONSE_REF_PREFIX)) {
            return response;
        }
        return doc.path("components").path("responses").path(ref.asString().substring(RESPONSE_REF_PREFIX.length()));
    }

    private Map<String, Object> content(JsonNode content) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : content.properties()) {
            String key = mediaType(e.getKey());
            if (m.put(key, schema(e.getValue().path("schema"))) != null) {
                throw new IllegalArgumentException("정규화 뒤 미디어 형식이 겹친다: " + key);
            }
        }
        return m;
    }

    /** 미디어 형식은 {@code ;} 앞까지 소문자로 본다({@code application/json;charset=UTF-8} = {@code application/json}). */
    static String mediaType(String raw) {
        int semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim().toLowerCase();
    }

    /**
     * 응답 헤더: 이름(대소문자 무시)·필수 여부·스키마. {@code Content-Type} 은 OpenAPI 규칙상 헤더 정의에서
     * 무시되므로 뺀다.
     */
    private Map<String, Object> headers(JsonNode headers) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : headers.properties()) {
            String name = e.getKey().toLowerCase();
            if (name.equals("content-type")) {
                continue;
            }
            JsonNode h = e.getValue();
            if (h.has("$ref")) {
                m.put(name, Map.of("$ref", h.get("$ref").asString()));
                continue;
            }
            Map<String, Object> hm = new TreeMap<>();
            hm.put("required", h.path("required").asBoolean(false));
            hm.put("schema", schema(h.path("schema")));
            m.put(name, hm);
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
