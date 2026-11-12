package antlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.RowSet;
import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowColumn;
import antlab.support.RowSet.RowQuery;
import antlab.support.SqlGoldenHarness;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성적표 골든 공용 도구 — SQL 정의 {@code sr_report}(26)의 37열을 한 시즌에서 읽고, 같은 정의를 API DB 에서 읽은 값 및 API 응답과 대조한다.
 * 비교 가능한 값은 모두 값 비교다(금액·비율은 {@code compareTo}). 값 자체는 어디에도 출력하지 않는다(어긋난 열 이름과 쪽만 보고한다).
 */
final class SeasonReportGoldenSupport {

    static final String DEFINITION = "db/26_season_report_def.sql";

    /** 피드 출발점이 SQL 과 Java 에서 다른 세 열(Q5-A) — SQL↔Java 대조에서만 빼고 반대쪽 값을 따로 단언한다. */
    static final Set<String> STARTING_POINT_COLUMNS = Set.of("nudge_share", "nudge_same_turn_share", "platform_nudge_fee");

    private static final Map<String, ColumnType> COLUMNS = new LinkedHashMap<>();

    static {
        String[] text = {"season_status"};
        String[] bool = {"is_final"};
        String[] longs = {"report_turns", "path_length", "pending_orders", "pending_changes", "risk_breach_turns",
                "role_players_judged", "role_players_unjudged", "formation_changes", "formation_change_orders",
                "changes_without_orders", "recon_mismatch_rows", "recon_turn_bundles", "recon_manual_bundles",
                "recon_out_of_range_rows"};
        for (String name : antlab.scoring.SeasonReportColumns.sqlColumns()) {
            ColumnType type = ColumnType.DECIMAL;
            if (List.of(text).contains(name)) {
                type = ColumnType.TEXT;
            } else if (List.of(bool).contains(name)) {
                type = ColumnType.BOOLEAN;
            } else if (List.of(longs).contains(name)) {
                type = ColumnType.LONG;
            }
            COLUMNS.put(name, type);
        }
    }

    private SeasonReportGoldenSupport() {
    }

    static RowQuery reportQuery() {
        List<RowColumn> columns = new ArrayList<>();
        COLUMNS.forEach((name, type) -> columns.add(new RowColumn(name, type)));
        return new RowQuery("sr_report", "SELECT " + String.join(", ", COLUMNS.keySet()) + " FROM pg_temp.sr_report(:sid)", columns);
    }

    /** 26 정의로 한 DB 의 한 시즌 성적표 한 행을 읽는다. */
    static RowSet sqlReport(SqlGoldenHarness harness, DatabaseHandle db, long seasonId) throws Exception {
        RowSet rows = harness.queryWithDefinitions(db, DEFINITION, reportQuery(), seasonId);
        assertThat(rows.size()).as("sr_report 한 행").isEqualTo(1);
        return rows;
    }

    static JsonNode apiReport(MockMvcTester mvc, long seasonId) throws Exception {
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, seasonId);
        assertThat(r.getStatus()).as("성적표 응답").isEqualTo(200);
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    private static String camel(String snake) {
        return antlab.scoring.SeasonReportColumns.camel(snake);
    }

    /** 두 행 모음의 같은 열을 비교한다. 같지 않은 열 이름만 모아 돌려준다(값은 싣지 않는다). */
    static List<String> differences(RowSet left, RowSet right, Set<String> skip) {
        List<String> out = new ArrayList<>();
        COLUMNS.forEach((name, type) -> {
            if (!skip.contains(name) && !same(type, left.get(0, name), right.get(0, name))) {
                out.add(name);
            }
        });
        return out;
    }

    /** SQL 행과 API 응답을 비교한다. 같지 않은 열 이름만 돌려준다. */
    static List<String> differences(RowSet sql, JsonNode response) {
        List<String> out = new ArrayList<>();
        COLUMNS.forEach((name, type) -> {
            JsonNode node = response.get(camel(name));
            Object actual = node == null || node.isNull() ? null : switch (type) {
                case DECIMAL -> new BigDecimal(node.asString());
                case LONG -> node.asLong();
                case BOOLEAN -> node.asBoolean();
                default -> node.asString();
            };
            if (!same(type, sql.get(0, name), actual)) {
                out.add(name);
            }
        });
        return out;
    }

    private static boolean same(ColumnType type, Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (type == ColumnType.DECIMAL) {
            return ((BigDecimal) left).compareTo((BigDecimal) right) == 0;
        }
        return left.equals(right);
    }

    /** 금액 열은 정수 문자열, 비율 열은 소수 8자리 문자열이다. DECIMAL 열은 정확히 한 집합에 속한다. */
    static void assertResponseFormat(JsonNode response) {
        Set<String> decimals = new java.util.TreeSet<>();
        COLUMNS.forEach((name, type) -> {
            if (type == ColumnType.DECIMAL) {
                decimals.add(camel(name));
            }
        });
        Set<String> classified = new java.util.TreeSet<>(ReportApiSupport.MONEY);
        classified.addAll(ReportApiSupport.RATIO);
        assertThat(classified).as("금액·비율 집합 합집합 = DECIMAL 열").isEqualTo(decimals);
        assertThat(ReportApiSupport.MONEY).as("금액·비율 집합은 겹치지 않는다").doesNotContainAnyElementsOf(ReportApiSupport.RATIO);
        response.propertyNames().forEach(key -> {
            JsonNode v = response.get(key);
            if (v.isNull()) {
                return;
            }
            if (ReportApiSupport.MONEY.contains(key)) {
                assertThat(v.asString()).as(key).matches(ReportApiSupport.MONEY_FORMAT);
            } else if (ReportApiSupport.RATIO.contains(key)) {
                assertThat(v.asString()).as(key).matches(ReportApiSupport.RATIO_FORMAT);
            }
        });
    }

    static Object value(RowSet rows, String column) {
        return rows.get(0, column);
    }

    /** 턴 1..path 마다 17행이 모두 MATCH 인 (턴, 시도) 묶음이 정확히 하나씩 있다. */
    static void assertOneMatchBundlePerTurn(SqlGoldenHarness harness, DatabaseHandle db, long season, int pathLength, String label)
            throws Exception {
        RowQuery q = new RowQuery("bundles", """
                SELECT turn_no AS turn_no, count(*) AS n FROM (
                    SELECT turn_no, attempt_no FROM reconciliation_log WHERE season_id = :sid
                    GROUP BY turn_no, attempt_no
                    HAVING count(*) = 17 AND count(*) FILTER (WHERE status = 'MATCH') = 17) b
                GROUP BY turn_no ORDER BY turn_no""", List.of(new RowColumn("turn_no", ColumnType.LONG), new RowColumn("n", ColumnType.LONG)));
        RowSet rows = harness.readRows(db, q, season);
        assertThat(rows.size()).as(label + " 일치 묶음이 있는 턴 수").isEqualTo(pathLength);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i, "turn_no")).as(label).isEqualTo((long) i + 1);
            assertThat(rows.get(i, "n")).as(label + " 턴 " + (i + 1) + " 일치 묶음 수").isEqualTo(1L);
        }
    }
}
