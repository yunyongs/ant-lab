package antlab.support;

import antlab.scoring.CheckCode;
import antlab.scoring.RuleResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL({@code recon_rules}) 결과와 Java 결과의 9컬럼 비교. 숫자는 {@code compareTo}(자릿수 무시, null 은 null 끼리),
 * 분류는 null ≠ 빈 값, detail 은 JSON 구조 비교. 실패 메시지에는 규칙·열 이름만 싣고 값은 싣지 않는다.
 */
public final class RuleResultAssert {

    private RuleResultAssert() {
    }

    /** 두 목록이 같으면 빈 목록, 다르면 "규칙번호/열" 문장 목록. */
    public static List<String> differences(List<RuleResult> expected, List<RuleResult> actual) {
        List<String> out = new ArrayList<>();
        if (expected.size() != actual.size()) {
            out.add("행 수 " + expected.size() + " vs " + actual.size());
            return out;
        }
        for (int i = 0; i < expected.size(); i++) {
            RuleResult e = expected.get(i);
            RuleResult a = actual.get(i);
            String at = "규칙 " + e.ruleNo() + "/";
            if (e.ruleNo() != a.ruleNo()) {
                out.add(at + "rule_no");
            }
            if (e.checkCode() != a.checkCode()) {
                out.add(at + "check_code");
            }
            if (e.status() != a.status()) {
                out.add(at + "status");
            }
            if (!sameDecimal(e.expectedValue(), a.expectedValue())) {
                out.add(at + "expected_value");
            }
            if (!sameDecimal(e.actualValue(), a.actualValue())) {
                out.add(at + "actual_value");
            }
            if (!sameDecimal(e.diffValue(), a.diffValue())) {
                out.add(at + "diff_value");
            }
            if (e.mismatchCount() != a.mismatchCount()) {
                out.add(at + "mismatch_count");
            }
            if (e.classification() != a.classification()) {
                out.add(at + "classification");
            }
            if (!sameJson(e.detail(), a.detail())) {
                out.add(at + "detail");
            }
        }
        return out;
    }

    public static void assertSame(List<RuleResult> sql, List<RuleResult> java) {
        List<String> diffs = differences(sql, java);
        if (!diffs.isEmpty()) {
            throw new AssertionError("SQL 과 Java 대사 결과가 다르다: " + diffs);
        }
    }

    /** 불일치(MISMATCH)인 검사 코드 집합. */
    public static Set<CheckCode> mismatchCodes(List<RuleResult> results) {
        return results.stream().filter(r -> r.mismatchCount() > 0).map(RuleResult::checkCode)
                .collect(Collectors.toSet());
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static boolean sameJson(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        JsonNode x = JsonMapper.shared().readTree(a);
        JsonNode y = JsonMapper.shared().readTree(b);
        return Objects.equals(x, y);
    }
}
