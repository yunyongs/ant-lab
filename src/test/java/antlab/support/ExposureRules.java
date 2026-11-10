package antlab.support;

import antlab.common.exposure.ExposedFrom;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 응답 record 가 노출 상한을 지키는지 검사한다: 모든 구성 요소가 {@code @ExposedFrom} 으로 원천 열을 밝히고,
 * 그 열의 태그가 {@code [노출]} 이거나 식별자 허용 목록에 있어야 한다. 날짜 순서 키는 영구 금지다.
 * 중첩 record·목록은 그 record 를 다시 검사한다. 위반은 사람이 읽는 문장 목록으로 돌려준다.
 */
public final class ExposureRules {

    /**
     * 식별자 허용 목록. 식별자 열(id·*_id)은 태그와 무관하게 이 목록으로만 통과한다. 이후 기능이 더한다.
     * asset.asset_group_id 는 부모 asset_group.id([내부])와 같은 값이지만 그룹 코드 순 적재라 날짜 단서가 없다.
     * season.id 는 시즌 생성 순서라 역사 날짜와 무관하다. turn_price.asset_id 는 이미 허용된 asset.id 와 같은 값이다.
     */
    public static final Set<String> IDENTIFIER_ALLOWED =
            Set.of("asset.id", "asset.asset_group_id", "season.id", "turn_price.asset_id");

    /** 영구 금지: 적재 순서가 달 순서라 번호 자체가 날짜 단서다(erd §2, SPRING_JPA §4.6). */
    public static final Set<String> FORBIDDEN = Set.of("month_block.id", "month_event.id", "turn_feed.month_event_id");

    private ExposureRules() {
    }

    /** 명시 목록, 모든 month_block_id 열, 그리고 month_block_id 열이 있는 표(달 순서로 적재됨)의 id. */
    static boolean forbidden(String source, ExposureCatalog catalog) {
        if (FORBIDDEN.contains(source) || source.endsWith(".month_block_id")) {
            return true;
        }
        return source.endsWith(".id") && catalog.has(table(source) + ".month_block_id");
    }

    private static String table(String source) {
        return source.substring(0, source.indexOf('.'));
    }

    private static boolean identifierColumn(String source) {
        String column = source.substring(source.indexOf('.') + 1);
        return column.equals("id") || column.endsWith("_id");
    }

    public static List<String> check(Class<?> record, ExposureCatalog catalog) {
        List<String> out = new ArrayList<>();
        walk(record, catalog, new HashSet<>(), out);
        return out;
    }

    private static void walk(Class<?> record, ExposureCatalog catalog, Set<Class<?>> seen, List<String> out) {
        if (!seen.add(record)) {
            return;
        }
        for (RecordComponent rc : record.getRecordComponents()) {
            String where = record.getSimpleName() + "." + rc.getName();
            Class<?> nested = nestedRecord(rc.getGenericType());
            ExposedFrom from = rc.getAnnotation(ExposedFrom.class);
            if (from == null) {
                if (nested == null) {
                    out.add(where + " — @ExposedFrom 없음");
                }
            } else {
                for (String source : from.value()) {
                    out.addAll(checkSource(where, source, catalog));
                }
            }
            if (nested != null) {
                walk(nested, catalog, seen, out);
            }
        }
    }

    static List<String> checkSource(String where, String source, ExposureCatalog catalog) {
        List<String> out = new ArrayList<>();
        if (forbidden(source, catalog)) {
            out.add(where + " — 영구 금지 원천 " + source);
        } else if (!catalog.has(source)) {
            out.add(where + " — 원천 열 " + source + " 이(가) DB 에 없음");
        } else {
            String tag = catalog.tag(source);
            if (identifierColumn(source)) {
                if (!IDENTIFIER_ALLOWED.contains(source)) {
                    out.add(where + " — 식별자 열 " + source + " 이(가) 식별자 허용 목록에 없음 (태그 [" + tag + "] 와 무관)");
                }
            } else if (!ExposureCatalog.EXPOSED.equals(tag)) {
                out.add(where + " — 원천 " + source + " 의 태그가 [" + tag + "] (허용: [노출] 또는 식별자 허용 목록)");
            }
        }
        return out;
    }

    /** 구성 요소가 record 이거나 record 의 목록이면 그 record, 아니면 null. */
    static Class<?> nestedRecord(Type type) {
        if (type instanceof Class<?> c && c.isRecord() && c.getName().startsWith("antlab.")) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                Class<?> found = nestedRecord(arg);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
