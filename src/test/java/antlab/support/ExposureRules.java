package antlab.support;

import antlab.common.exposure.ExposedFrom;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 응답 record 가 노출 상한을 지키는지 검사한다: 모든 구성 요소가 {@code @ExposedFrom} 으로 원천 열을 밝히고,
 * 그 열의 태그가 {@code [노출]} 이거나 식별자 허용 목록에 있어야 한다. 날짜 순서 키는 영구 금지다.
 * 중첩 record·목록은 그 record 를 다시 검사한다. 위반은 사람이 읽는 문장 목록으로 돌려준다.
 *
 * <p>예외(P0-32): {@code local} 프로필에서만 열리는 개발 엔드포인트(수동 대사)의 응답은 검사 대상이 아니다 —
 * 코드·상태·건수·시도 번호({@code [내부]} 열)를 내지만 공개 계약이 아닌 개발 도구이고, 기대·실제·차이·detail 값은 내지 않는다.
 */
public final class ExposureRules {

    /**
     * 식별자 허용 목록. 식별자 열(id·*_id)은 태그와 무관하게 이 목록으로만 통과한다. 이후 기능이 더한다.
     * asset.asset_group_id 는 부모 asset_group.id([내부])와 같은 값이지만 그룹 코드 순 적재라 날짜 단서가 없다.
     * season.id 는 시즌 생성 순서라 역사 날짜와 무관하다. turn_price.asset_id 는 이미 허용된 asset.id 와 같은 값이다.
     * orders.id 는 시즌 안의 주문 생성 순서일 뿐 역사 달과 무관하다. orders.formation_change_id 는 시즌 안의 전술 변경 순서로,
     * 주문이 어느 변경에 묶였는지만 알려 주고 역사 날짜 단서가 없다(P0-31).
     */
    public static final Set<String> IDENTIFIER_ALLOWED = Set.of("asset.id", "asset.asset_group_id", "season.id",
            "turn_price.asset_id", "orders.id", "orders.formation_change_id");

    /** 영구 금지: 적재 순서가 달 순서라 번호 자체가 날짜 단서다(erd §2, SPRING_JPA §4.6). */
    public static final Set<String> FORBIDDEN = Set.of("month_block.id", "month_event.id", "turn_feed.month_event_id");

    /**
     * 끝난 시즌 전용 원천(P0-38): 경로의 달 순서로 잰 집계 값(역할 수행)이 나오는 열. 이 열을 쓰는 응답은 핸들러에
     * {@code @FinishedSeasonOnly} 가 있어야 하고(진행 중 시즌은 409), 다른 핸들러는 여전히 영구 금지다. 구성 요소는 ① 형이
     * {@code BigDecimal}·{@code Integer}·{@code int} 이고 ② 같은 {@code @ExposedFrom} 에 이 목록 밖의 원천이 하나 이상이어야 한다 —
     * 경로 순서는 집계의 입력일 뿐 단독 값이 아니다(블록 번호 그대로의 노출은 SPRING_JPA §4.6 의 영구 금지). P0-39 복기가 같은 장치를 넓혀 쓴다.
     */
    public static final Set<String> FINISHED_ONLY_ALLOWED = Set.of("season_path.month_block_id");

    /**
     * 건수 전용 원천(P0-38): 대사 로그의 상태·턴·시도는 [내부]이지만 성적표는 행·묶음 <b>개수</b>만 낸다(값·detail 없음).
     * 이 열을 쓰는 구성 요소는 형이 {@code Integer}(또는 {@code int})여야 하고, 핸들러에 {@code @FinishedSeasonOnly} 가 있어야 한다.
     */
    public static final Set<String> COUNT_ONLY_ALLOWED = Set.of("reconciliation_log.status", "reconciliation_log.turn_no",
            "reconciliation_log.attempt_no");

    /**
     * Reproducibility triple of a season. Tagged {@code [내부]} in the DB (true while the season runs) but allowed on the
     * one handler that carries both {@code @FinishedSeasonOnly} and {@code @ReviewReveal}.
     */
    public static final Set<String> REVIEW_INTERNAL_ALLOWED = Set.of("season.seed", "season.engine_version",
            "season.data_snapshot_date");

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

    /** 표지 없는 핸들러의 응답 검사 — 끝난 시즌 전용·건수 전용 원천은 허용되지 않는다. */
    public static List<String> check(Class<?> record, ExposureCatalog catalog) {
        return check(record, catalog, false);
    }

    /** {@code finishedSeasonOnly} 는 핸들러에 {@code @FinishedSeasonOnly} 가 있는지다. */
    public static List<String> check(Class<?> record, ExposureCatalog catalog, boolean finishedSeasonOnly) {
        return check(record, catalog, finishedSeasonOnly, false);
    }

    /** {@code reviewReveal} is whether the handler also has {@code @ReviewReveal}; it only counts together with {@code finishedSeasonOnly}. */
    public static List<String> check(Class<?> record, ExposureCatalog catalog, boolean finishedSeasonOnly,
            boolean reviewReveal) {
        List<String> out = new ArrayList<>();
        walk(record, catalog, finishedSeasonOnly, reviewReveal, new HashSet<>(), out);
        return out;
    }

    private static void walk(Class<?> record, ExposureCatalog catalog, boolean finishedSeasonOnly, boolean reviewReveal,
            Set<Class<?>> seen, List<String> out) {
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
                    out.addAll(checkRestricted(where, source, from.value(), rc.getType(), catalog, finishedSeasonOnly,
                            reviewReveal));
                }
            }
            if (nested != null) {
                walk(nested, catalog, finishedSeasonOnly, reviewReveal, seen, out);
            }
        }
    }

    /** 끝난 시즌 전용·건수 전용 원천은 표지와 형 조건으로만 통과시키고, 나머지는 {@link #checkSource} 로 보낸다. */
    static List<String> checkRestricted(String where, String source, String[] sources, Class<?> type, ExposureCatalog catalog,
            boolean finishedSeasonOnly, boolean reviewReveal) {
        if (finishedSeasonOnly && reviewReveal && revealed(source, catalog)) {
            return List.of();
        }
        boolean finishedOnly = FINISHED_ONLY_ALLOWED.contains(source);
        boolean countOnly = COUNT_ONLY_ALLOWED.contains(source);
        if (!finishedOnly && !countOnly) {
            return checkSource(where, source, catalog);
        }
        List<String> out = new ArrayList<>();
        if (!catalog.has(source)) {
            out.add(where + " — 원천 열 " + source + " 이(가) DB 에 없음");
        } else if (!finishedSeasonOnly) {
            out.add(where + " — " + (finishedOnly ? "끝난 시즌 전용" : "건수 전용") + " 원천 " + source
                    + " 은(는) @FinishedSeasonOnly 핸들러에서만 쓸 수 있음");
        } else if (countOnly && type != Integer.class && type != int.class) {
            out.add(where + " — 건수 전용 원천 " + source + " 의 구성 요소 형이 Integer 가 아님 (" + type.getSimpleName() + ")");
        } else if (finishedOnly && !aggregateOfOthers(type, sources)) {
            out.add(where + " — 끝난 시즌 전용 원천 " + source + " 은(는) 다른 원천과 함께 집계한 BigDecimal·Integer 값에만 쓸 수 있음 ("
                    + type.getSimpleName() + ")");
        }
        return out;
    }

    /**
     * Sources a review handler may expose: the reproducibility triple and non-identifier {@code [복기 전용]} value columns.
     * Identifier columns (block and event ids, join keys) never pass here, so the permanent bans still fire.
     */
    private static boolean revealed(String source, ExposureCatalog catalog) {
        if (!catalog.has(source)) {
            return false;
        }
        if (REVIEW_INTERNAL_ALLOWED.contains(source)) {
            return true;
        }
        return ExposureCatalog.REVEAL_ONLY.equals(catalog.tag(source)) && !identifierColumn(source)
                && !forbidden(source, catalog);
    }

    /** 끝난 시즌 전용 원천의 조건: 형이 BigDecimal·Integer·int 이고, 같은 {@code @ExposedFrom} 에 전용 목록 밖의 원천이 하나 이상이다. */
    private static boolean aggregateOfOthers(Class<?> type, String[] sources) {
        boolean numeric = type == BigDecimal.class || type == Integer.class || type == int.class;
        boolean mixed = Arrays.stream(sources).anyMatch(s -> !FINISHED_ONLY_ALLOWED.contains(s));
        return numeric && mixed;
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
