package antlab.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정규화한 두 계약(저장소 yaml / 코드가 만든 명세)의 차이 목록. 실패 메시지는 줄 단위
 * {@code 포인터 — yaml: … / code: …} 이고 차이가 없으면 빈 목록이다.
 * yaml 에 범위 안 운영이 닿지 않는 컴포넌트(고아)가 있으면 그것도 차이 한 줄이다.
 */
public final class OpenApiDiff {

    private OpenApiDiff() {
    }

    public static List<String> diff(JsonNode yamlDoc, JsonNode codeDoc) {
        Map<String, Object> yaml = OpenApiNormalizer.normalize(yamlDoc);
        Map<String, Object> code = OpenApiNormalizer.normalize(codeDoc);
        List<String> out = new ArrayList<>();
        compare("", yaml, code, out);
        if (!((List<?>) yaml.get("orphans")).isEmpty() && out.stream().noneMatch(l -> l.startsWith("/orphans"))) {
            out.add(line("/orphans", show(yaml.get("orphans")), "(yaml 에만 있는 고아 컴포넌트)"));
        }
        return out;
    }

    private static void compare(String pointer, Object y, Object c, List<String> out) {
        if (y instanceof Map<?, ?> ym && c instanceof Map<?, ?> cm) {
            TreeSet<String> keys = new TreeSet<>();
            ym.keySet().forEach(k -> keys.add((String) k));
            cm.keySet().forEach(k -> keys.add((String) k));
            for (String key : keys) {
                String next = pointer + "/" + key;
                boolean inY = ym.containsKey(key);
                boolean inC = cm.containsKey(key);
                if (inY && inC) {
                    compare(next, ym.get(key), cm.get(key), out);
                } else {
                    out.add(line(next, inY ? show(ym.get(key)) : "(없음)", inC ? show(cm.get(key)) : "(없음)"));
                }
            }
        } else if (!y.equals(c)) {
            out.add(line(pointer, show(y), show(c)));
        }
    }

    private static String line(String pointer, String yaml, String code) {
        return pointer + " — yaml: " + yaml + " / code: " + code;
    }

    private static String show(Object o) {
        return JsonMapper.shared().writeValueAsString(o);
    }
}
